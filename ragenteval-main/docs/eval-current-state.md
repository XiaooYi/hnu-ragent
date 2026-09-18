# RAGent 当前测评方式总览（供外部 Review）

> 用途：把"现在到底是怎么测的"原原本本写清楚，便于其他大模型 / 同事审阅，给出优化或重构建议。
> 范围：Ragent 服务端的评测旁路接口 + ragenteval 侧的 runner / 指标 / 报告。
> 版本：v1（2026-05-21）。

---

## 1. 全景

两个仓库协作完成一次评测：

| 仓库 | 角色 | 关键产物 |
|---|---|---|
| `ragent` | 被评测系统。暴露一个**纯检索**评测旁路接口 + 正常的 SSE 对话接口 | `/rag/eval`、`/rag/v3/chat` |
| `ragenteval` | 评测项目。维护评估集、跑 runner、算指标、出报告 | `eval_set_v1.jsonl`、`runner.py`、`metrics/*`、`reports/*` |

**链路**：

```
eval_set_v1.jsonl (150 条)
        │
        ▼
   runner.py ──► /rag/v3/chat (SSE)   ─► response / thinking / TTFT / latency
              └► /rag/eval     (JSON) ─► 检索证据（docIds / chunks / contexts / intent / mcp）
        │
        ▼
   runs/v1_<ts>.jsonl
        │
        ├──► intent.py / retrieval.py / behavior.py / latency.py  (自建指标)
        ├──► ragas_judge.py   (5 个 RAGAS LLM-as-judge)
        └──► slides.py        (16:9 HTML 汇报)
                │
                ▼
        reports/v1_<ts>/{report.md, per_sample.csv, failures.jsonl, _scores.json, slides.html}
```

---

## 2. Ragent 侧：评测旁路接口

### 2.1 设计取舍

- **只跑检索、不跑 LLM**。`EvalController` 直接组合 `QueryRewriteService` → `IntentResolver` → `RetrievalEngine`，**不走 `StreamChatPipeline`**。
- **答案生成由真实生产接口 `/rag/v3/chat` (SSE) 承担**，由 runner 自己聚合 token。这样既能复用线上链路、又能拿到首字耗时（TTFT）。
- 评测旁路通过 `app.eval.enabled=true` 开关；关闭时 `EvalController` 不注册，生产零开销。

> 这是当前实现，**与 `docs/eval-controller-plan.md` 的"双轨制 + AOP 旁路捕获"方案不一致**。计划文档曾倾向于 POST 同步接口 + 切面捕获，实际落地走了更简单的"独立 GET 接口跑检索子链路"。需要 review 决定是否把计划文档归档或对齐。

### 2.2 接口规格

```
GET /api/ragent/rag/eval?question=<query>
Authorization: <sa-token>
```

返回 `EvalResponse`（`bootstrap/.../rag/eval/EvalResponse.java`）：

| 字段 | 说明 |
|---|---|
| `retrievedDocIds` | 业务文档 ID 列表（已去重）。由 `t_knowledge_document.doc_name` 剥文件后缀得到，对齐评估集 `expected_doc_ids` |
| `retrievedChunkIds` | chunk 主键列表（`RetrievedChunk.id`），与 `retrievedContexts` 顺序对应、去重 |
| `retrievedContexts` | chunk 文本列表 |
| `retrievedContextDocIds` | **chunk 维度**的业务 docId，长度与 `retrievedContexts` 严格相等、保留 `null`、不去重；评测脚本算 chunk 级指标用 |
| `mcpContext` / `hasMcp` / `hasKb` | 区分 KB / MCP / 混合分支，便于评测端诊断 |
| `subIntents` | 改写后的子问题文本列表 |
| `intentLeafIds` | 每个子问题 top-1 意图叶子节点 id（与评估集 `intent_l2` 比对） |
| `latencyMs` | 检索子链路耗时（**不含 LLM**） |

### 2.3 ID 映射（关键，易错点）

`chunkId → docId` 两跳：

```
RetrievedChunk.id
     │ 一跳：t_knowledge_chunk.doc_id（雪花 ID）
     ▼
内部 docId
     │ 二跳：t_knowledge_document.doc_name → 剥扩展名
     ▼
业务码（如 FAQ_VAC_001）
```

`docName` 是约定的"业务码 + 文件后缀"形式，约定本身没有强校验。

---

## 3. 评测项目侧：评估集

`eval/rag/dataset/eval_set_v1.jsonl`，150 条，每条字段：

| 字段 | 含义 |
|---|---|
| `query_id` | 稳定样本 ID（如 `S1-01`、`F1-01`） |
| `query` | 用户原始问题 |
| `intent_l1` / `intent_l2` | 一/二级意图（如 `SUPPORT` / `S1_选购推荐`） |
| `difficulty` | `easy` / `medium` / `hard` |
| `requires_rag` | 是否应该走 RAG 检索（false = 应该走 SYSTEM 兜底话术） |
| `expected_doc_ids` | **must**：必须召回的最小核心证据集 |
| `expected_doc_ids_nice` | **nice**：扩展证据，可缺省。GUIDE 已含其关键参数的 PROD 通常进这里 |
| `ground_truth` | 标准答案（RAGAS 的 `reference`） |
| `expected_answer_type` / `trap_type` / `eval_metrics` | 元数据，目前不直接驱动指标计算 |

**已知数据质量问题**（README 自承）：
- 150 条里只有 S1-01..S1-05 按 v1.1 规范重标了 must/nice，其余 145 条把全部期望都塞进 `expected_doc_ids`，nice 视为空集（向后兼容）。
- 大部分 `ground_truth` 仍是"应推荐..."/"应命中..."这种**元指令**格式，不是真实自然语言答案，会让 RAGAS `answer_correctness` 偏低。

**没有的字段**：`tool_calls_gold`（所以本版不评 Tool Calling）、`should_refuse`（用 `requires_rag=false` 近似）。

---

## 4. Runner：双接口聚合

`eval/rag/pipeline/runner.py` 对每条样本顺序发两次请求：

1. **`GET /rag/v3/chat` (SSE)** — 真实生产链路
   - 自己实现 SSE 解析（`parse_sse_stream`），按 `\n\n` / `\r\n\r\n` 切事件，避开 `requests.iter_lines()` 跨 chunk 吞空行的坑
   - 聚合 `type=response` 的 delta 拼成 `response`，`type=think` 拼成 `thinking`
   - **首字打点**：第一个 `type=response` 且 `content` 非空的 delta 到达时记 `first_token_ms`，**不算思考链路**
   - `latency_ms` = 整条流结束的总耗时
   - `final_status` 由 `finish` / `reject` / `cancel` 事件决定
   - 超时：连接 15s / 读 300s（与 ragent `sse-timeout-ms` 对齐）

2. **`GET /rag/eval` (JSON)** — 评测旁路
   - 拿检索证据
   - 同时做一次 `ragent_doc_id → 业务 id` 反向映射（`doc_id_map.json`，由 init 阶段灌库时生成）

合并落 `runs/v1_<ts>.jsonl`，字段命名对齐 RAGAS 0.2+ 约定（`user_input` / `response` / `retrieved_contexts` / `reference`）。

**已知约束**：
- runner 串行跑，每条之间 `sleep 0.3s` 避开 ragent 全局限流（`rag.rate-limit.global.max-concurrent=10`）。
- **两次请求是两次独立检索**：`/rag/v3/chat` 内部跑一次完整检索 + LLM，`/rag/eval` 再跑一次纯检索。两次的召回结果**不保证完全一致**（受随机性、缓存、改写抖动影响），但实践上接近。这是当前最大的设计妥协。

---

## 5. 指标层

### 5.1 自建指标（`intent.py` / `retrieval.py` / `behavior.py` / `latency.py`，纯标准库、秒级出结果）

| 指标 | 定义 | 仅统计 |
|---|---|---|
| **意图 Top-1 准确率** | `intent_pred == intent_l2` 的占比 | 全量 |
| **Hit@K**（K=1/3/5/10） | `retrieved_doc_ids[:K] ∩ reference_doc_ids` 非空 | `requires_rag=true` 且 `reference_doc_ids` 非空 |
| **Recall@K (must)** | 主指标 | 同上 |
| **Recall@K (inclusive)** | 把 `expected_doc_ids_nice` 也算进期望集；nice 缺省时退化为 must | 同上 |
| **MRR@10** | 第一条命中文档排名的倒数 | 同上 |
| **误拒率** | `requires_rag=true` 但 `retrieved_doc_ids` 为空的占比 | `requires_rag=true` |
| **答案兜底率** | `requires_rag=true` 但 response 含"未检索到与问题相关的文档内容" | 同上 |
| **错答率（过召回）** | `requires_rag=false` 却走了 RAG 召回 | `requires_rag=false` |
| **首字 P50/P95/P99/均值** | `first_token_ms` 的分位数 | 全量（取不到则回退 `latency_ms`） |
| **整流 P95** | `latency_ms` 的 P95，仅参考 | 全量 |

**关键设计选择**：
- **P95 看 TTFT（首字），不看整流**。对话产品体感卡点在首字到达，整流时长随 token 数线性增长，不反映"卡顿"。
- **检索指标按 `requires_rag` 切**，SYSTEM 话术类样本不污染 Hit/Recall/MRR。
- **答案兜底率 vs 误拒率**：误拒看检索是否为空，兜底看生成兜底话术是否被触发；两个数都报，反映检索失败的两种姿态。

**Sanity check**（`check_doc_id_ordering`）：把 ragent 返回的 `retrievedContextDocIds`（chunk 维度）与从 `retrieved_contexts` frontmatter 正则提取的 `doc_id` 逐位比对，验证 ragent 端 `chunkId → docId` 映射的正确性。不一致仅告警、不阻塞。

**分层报告**：
- `report.md` 整体一页纸（自建 + RAGAS + 按意图分层）
- `per_sample.csv` 每条样本所有指标横向铺开
- `failures.jsonl` Hit@5 miss / correctness 低 / 误拒 / 过召回的样例
- `slides.html` 16:9 HTML 汇报

### 5.2 RAGAS 指标（`ragas_judge.py`，LLM-as-judge）

5 个指标，固定使用 LLM-with-reference 变体：

| 指标 | 衡量 |
|---|---|
| `faithfulness` | response 是否忠实于 retrieved_contexts（幻觉检测） |
| `answer_relevancy` | response 是否切题（反向生成问题与 user_input 余弦相似度，**不检测正确性**） |
| `answer_correctness` | response 与 reference 语义+事实一致（0.75 × claim F1 + 0.25 × similarity） |
| `context_precision` | 召回里有用信息比例（LLM 评判每个 chunk） |
| `context_recall` | 召回是否覆盖 reference 所需信息 |

**Judge 配置**：
- Provider: `aihubmix`（OpenAI 兼容端点）
- Judge model: `gpt-5.4-mini`（环境变量 `JUDGE_MODEL` 可覆盖）
- Embedding: `qwen3-embedding-8b`
- RunConfig: `max_retries=3, timeout=180`
- API key 需通过环境变量 `AIHUBMIX_API_KEY` 传入

**样本过滤**：只评 `response / retrieved_contexts / reference` 都非空且 `final_status=success` 的样本。其余跳过并在报告里列原因。

**产物**：RAGAS 分数集成到统一的 `per_sample.csv` 和 `report.md` 中，不单独出文件。

**已知约束**：
- **被评模型 vs Judge 同源风险**：被评模型默认 `gpt-5.4`（aihubmix），judge 默认 `gpt-5.4-mini`（aihubmix），**同 provider 同族**，有同源偏置风险。
- 单次跑有 3-5% 方差。支持 `--ragas-n 3` 并发跑多次取均值压制方差。
- 单条 5 指标 ≈ 15 次 judge 调用；150 条 ≈ 2250 次/轮。`--limit / --ragas-limit` 控成本。

### 5.3 报告渲染（`report/slides.py` + `report/markdown.py`）

按 `guizang-ppt-skill` 模板（瑞士国际主义 / 电子杂志风）出 16:9 HTML，浏览器横向翻页。展示：封面 → 4 大 KPI → 次级 KPI → 按二级意图分层 → 失败样例 → 样本明细 → 收束。

---

## 6. 不评估的东西（明确取舍）

| 维度 | 不评的理由 |
|---|---|
| Tool Calling 准确率 | 评估集没标 `tool_calls_gold` |
| 子问题拆分 / 改写质量 | 过程指标，只看最终检索/答案是否 OK |
| 多轮对话 | 评估集都是单轮 |
| 安全/有害内容 | 不在当前业务红线内 |
| token 成本 | runner 没采 `token_usage` 字段 |

---

## 7. 一页纸看板（参考目标）

| 维度 | 指标 | 来源 | 参考目标 |
|---|---|---|---|
| 意图 | Top-1 准确率 | 自建 | ≥ 92% |
| 检索 | Doc Hit@5 | 自建 | ≥ 90% |
| 检索 | Recall@5 (must) | 自建 | — |
| 检索 | context_recall | RAGAS | ≥ 0.80 |
| 检索 | context_precision | RAGAS | ≥ 0.75 |
| 生成 | faithfulness | RAGAS | ≥ 0.90 |
| 生成 | answer_correctness | RAGAS | ≥ 0.80 |
| 生成 | answer_relevancy | RAGAS | ≥ 0.85 |
| 行为 | 误拒率 | 自建 | ≤ 3% |
| 行为 | 错答率（过召回） | 自建 | ≤ 3% |
| 性能 | 首字 P95 (TTFT) | 自建 | ≤ 6s |

---

## 8. 已知设计妥协 / 想被 Review 的点

> 这一节是写给 reviewer 的，列出当前实现里**自己也不太满意 / 待决策**的地方。

1. **答案与检索不同源**：runner 调两个接口拿两份独立的检索结果，`/rag/v3/chat` 内部走真实链路、`/rag/eval` 单独再跑一次。结果近似但不严格一致。原 `eval-controller-plan.md` 提过"双轨制 + AOP 旁路捕获 + 同步 JSON 接口"方案能拿到同一轮检索的结果，但实现复杂、未落地。
2. **Judge 同源偏置**：被评 `gpt-5.4` 与 judge `gpt-5.4-mini` 同 provider 同族。RAGAS 实践要求换族（如 GPT 评 GPT 之外的模型）。
3. **RAGAS 方差未消除**：单跑一次就出报表，没有"3 次取均值"机制。
4. **`ground_truth` 元指令格式**：150 条里 145 条仍是"应推荐..."/"应命中..."，会系统性拉低 `answer_correctness`。需要补写真实自然语言答案。
5. **`expected_doc_ids` 单一字段**：only 5 条按 must/nice 分层，145 条还是单字段。Recall(inclusive) 退化为 Recall(must)。
6. **`docName` 剥后缀的隐含约定**：业务码必须等于 `docName` 去除最后一个 `.` 后缀部分，没有强校验，重名/无后缀会静默拿错值。
7. **Sanity check 不阻塞**：chunk 维度 docId 映射错位时仅 stderr 告警，不影响指标输出。Hit@1/MRR 在这种情况下会失真但没人发现。
8. **不评 Tool Calling**：MCP 调用结果完全游离在评测之外，只在响应里有个 `hasMcp` 旗标。
9. **评测旁路 `/rag/eval` 与生产链路 `/rag/v3/chat` 走不同代码**：前者是 `EvalController` 手工拼装 `QueryRewriteService → IntentResolver → RetrievalEngine`，后者走 `StreamChatPipeline`。**任何 pipeline 内部的检索改造（重排、过滤、合并）都不会反映在评测旁路**，存在漂移风险。
10. **API key 依赖环境变量**：`AIHUBMIX_API_KEY` 缺失时 RAGAS 直接报错，不再走兜底硬编码 key。
11. **runner 默认串行**：支持 `-w N` 多线程并行，但需注意 ragent 全局限流。

---

## 9. 关键文件索引

**ragent 侧**：
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/EvalController.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/EvalResponse.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/EvalProperties.java`
- `bootstrap/src/main/resources/application.yaml`（`app.eval.enabled`）
- `docs/evaluation-plan.md`（评测总体规划，与实际实现有出入）
- `docs/eval-controller-plan.md`（原计划：双轨制 + AOP，**未落地**）
- `scripts/verify_eval_context_docids.sh`（验证 chunk 维度 docId 对齐）

**评测项目侧**（`ragenteval`）：
- `eval/common/schemas.py`（核心数据模型）
- `eval/common/cli.py`（CLI 入口）
- `eval/rag/pipeline/runner.py`（录制）
- `eval/rag/pipeline/score.py`（指标编排）
- `eval/rag/dataset/eval_set_v1.jsonl`（150 条评估集）
- `eval/rag/metrics/intent.py` / `retrieval.py` / `behavior.py` / `latency.py`（自建指标）
- `eval/rag/metrics/ragas_judge.py`（RAGAS LLM-as-judge）
- `eval/rag/report/markdown.py` / `slides.py`（报告产出）
- `eval/rag/init/`（一次性初始化脚本）
- `eval/rag/dataset/doc_id_map.json`（`业务码 → ragent doc_id` 映射，本地生成）
- `README.md`（项目总览）
