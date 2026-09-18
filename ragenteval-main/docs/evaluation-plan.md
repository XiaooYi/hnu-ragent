# RAGent 系统评测规划

> 用途：明确系统评测需要采集哪些数据、哪些指标由 RAGAS 直接覆盖、哪些必须自建。
> 前置条件：评估集（150 条）、意图体系、文档体系（115 篇）已在前置项目完成，本项目只消费评估集并跑评测。
> 适用版本：v1.1（2026-05-16）

---

## 1. 评测原则

**以结果为导向，分层只保留"会决定上线与否"的指标。** 子问题拆分、查询改写这些都是过程指标——只要最终检索/答案 OK，过程是怎么拆的不重要。

整条链路只看四层：**意图 → 检索 → 工具/生成 → 性能**。

---

## 2. 评估集需要承载的字段

> 命名对齐 RAGAS `EvaluationDataset` 约定，可直接喂入。

| 字段 | 含义 | 用途 |
|---|---|---|
| `id` | 用例 ID（EVAL_001..150） | 全流程 |
| `user_input` | 原始用户问题 | 全流程 |
| `intent_gold` | 真值意图标签（S1~S17/F1） | 自建：意图准确率 |
| `tool_calls_gold` | 真值工具调用 `[{tool, args}]`（无需调用则为空） | 自建：Tool 准确率 |
| `reference_doc_ids` | 真值召回文档 ID | 自建：Doc Hit@K |
| `reference` | 标准答案 | RAGAS context_precision / context_recall / answer_correctness 都靠它 |
| `should_refuse` | 是否应拒答（OOD/无文档支撑） | 自建：误拒率 |
| `scenario_tag` | 场景标签（S2/S6/...） | 自建：分层报表 |

**运行时产物**（runner 跑完后追加到同一条记录上）：

| 字段 | 来源 |
|---|---|
| `response` | LLM 最终回答 |
| `retrieved_contexts` / `retrieved_doc_ids` | 召回 chunk 文本 + doc_id |
| `intent_pred` | `DefaultIntentClassifier` 输出 |
| `tool_calls_pred` | MCP 调用轨迹（`StreamChatTraceRunner` 已有 trace） |
| `latency_ms` | 端到端耗时 |
| `token_usage` | prompt + completion |
| `final_status` | success / refused / error |

---

## 3. RAGAS 用哪几个指标（精选 5 个）

> RAGAS 指标几十个，但跑得起、看得懂、能驱动迭代的只有这 5 个。其他（`context_entity_recall` / `answer_similarity` / `noise_sensitivity` / multi-turn 系列）一律忽略。

| 指标 | 衡量什么 | 依赖字段 | 不达标说明 |
|---|---|---|---|
| `faithfulness` | 回答是否忠实于召回（幻觉检测） | `response`, `retrieved_contexts` | LLM 编造——查 prompt 约束 / 模型 |
| `answer_relevancy` | 回答是否切题（兼测冗余啰嗦） | `response`, `user_input` | 跑偏——查 prompt 是否被 history 污染 |
| `context_precision` | 召回里有用信息的比例 | `retrieved_contexts`, `reference` | 召回掺水——查 rerank / topK 过大 |
| `context_recall` | 召回是否覆盖了标准答案所需信息 | `retrieved_contexts`, `reference` | 召回漏了——查 chunk 切分 / 路由策略 |
| `answer_correctness` | 回答与标准答案的语义+事实一致 | `response`, `reference` | 端到端答案差——综合性指标 |

**变体选型**：RAGAS 的 `context_precision` / `context_recall` 都有 LLM 版和 Non-LLM 版。**统一使用 LLM-with-reference 变体**（依赖 `reference` 标准答案），实现侧在 `ragas_eval.py` 里固定 import。

**关于 `answer_relevancy` 的局限**：算法是从答案反向生成问题，再与原 `user_input` 算余弦——**不检测正确性**，答错了但切题也能拿高分。必须和 `answer_correctness` 配套读，单看会误判。

**为什么不要 `answer_similarity`**：`answer_correctness = 0.75 × 事实 F1 + 0.25 × similarity`，已经把语义相似度算进去了，单独跑冗余。

**RAGAS 使用注意**：
- 全靠 LLM-as-judge。Judge 模型必须与被评模型**不同源**（避免同源偏置），建议固定一个 GPT-4 级别模型。
- 评分有方差，跑 3 次取均值再比较，单次差 3-5% 不算退化。
- 跑全量前先在 10 条上验证 judge 调用稳定（中文场景偶发 NaN）。

---

## 4. 必须自建的指标（4 项质量 + 1 项工程）

RAGAS 不覆盖、但**直接决定业务可用性**的四项质量指标，外加一项上线硬约束的工程指标：

假设我们有一个问题，**真实相关文档**（ground truth）是：

```text
相关文档集合 = {D3, D7, D9}   # 共 3 个
```

检索系统返回 Top-5 结果（按相关性排序）：

```text
排名:  1    2    3    4    5
文档:  D1   D3   D5   D7   D8
相关:  ❌   ✅   ❌   ✅   ❌
```

### 4.1 意图分类准确率
- **指标**：Top-1 Accuracy。
- **为何要**：意图错则后续全错，最上游的闸门。RAGAS 完全不管分类。
- **算法**：`intent_pred == intent_gold` 的比例；多标签则用 micro-F1。

### 4.2 检索 Doc-级 Hit@K
- **指标**：Hit@5（必看）、MRR（可选）。
- **为何要**：RAGAS context_recall 是 LLM 评判，贵且有方差。Doc-级命中是纯集合运算，秒出，**适合每次提交都跑**。和 RAGAS recall 双指标对照：Doc 命中但 RAGAS recall 低 ⇒ 召回到了但 chunk 切错。
- **算法**：`retrieved_doc_ids[:5] ∩ reference_doc_ids` 是否非空。

**Hit@K（命中率）** —— 是否命中

**定义**：Top-K 结果中，**只要有至少 1 个相关文档**，就算命中（1），否则为 0。

```text
Hit@5 = 1   (因为 Top-5 中有 D3、D7)
```

**特点**：

- 二值指标：**要么 1，要么 0**
- 多个 query 取平均，得到整体 Hit Rate
- **最宽松**的指标

**Recall@K（召回率）** —— 找回了多少

**定义**：Top-K 中**相关文档数量** ÷ **全部相关文档数量**

```text
Recall@5 = 命中的相关文档数 / 总相关文档数
        = 2 / 3
        ≈ 0.67
```

**特点**：

- 关注**覆盖完整性**
- 0 到 1 之间的连续值
- **比 Hit@K 更严格**（要找全）

**MRR（Mean Reciprocal Rank，平均倒数排名）** —— 第一个答案多靠前

**定义**：第一个相关文档**排名的倒数**，多个 query 取平均。

```text
公式：MRR = (1/N) × Σ (1 / rank_i)

我们的例子：第一个相关文档 D3 排在第 2 位
单条 RR = 1/2 = 0.5

如果有 3 个 query，第一个相关文档分别排在 1、2、5 位：
MRR = (1/1 + 1/2 + 1/5) / 3 = (1 + 0.5 + 0.2) / 3 ≈ 0.567
```

**特点**：

- **只看第一个相关文档的位置**
- 越靠前，分数越高
- 排名第 1 = 1.0；排名第 2 = 0.5；排名第 10 = 0.1

### 4.3 Tool Calling 准确率

- **指标**：
  - 决策准确率（该调没调 / 不该调乱调）；
  - 工具名 + 关键参数 Exact Match。
- **为何要**：MCP 调用错了答案就是错的，RAGAS 完全不评。`StreamChatTraceRunner` 的 trace 直接消费即可。
- **算法**：`tool_calls_pred` vs `tool_calls_gold`，先比决策（空/非空），再比 (tool_name, args)。

### 4.4 拒答正确性
- **指标**：误拒率（应答的拒答了）、错答率（应拒答的乱答了）。
- **为何要**：业务红线。评估集里专门标 `should_refuse=true` 的样例驱动这两个数。
- **算法**：基于 `final_status` 和 `should_refuse` 交叉。

### 4.5 性能与成本（附加，必看）
- **指标**：P95 延迟。
- **为何要**：上线硬约束。Runner 直接打点。

---

## 5. 一页纸看板

| 维度 | 指标 | 来源 | 参考目标 |
|---|---|---|---|
| 意图 | Top-1 准确率 | 自建 | ≥ 92% |
| 检索 | Doc Hit@5 | 自建 | ≥ 90% |
| 检索 | context_recall | RAGAS | ≥ 0.80 |
| 检索 | context_precision | RAGAS | ≥ 0.75 |
| 工具 | Tool 调用准确率 | 自建 | ≥ 90% |
| 生成 | faithfulness | RAGAS | ≥ 0.90 |
| 生成 | answer_correctness | RAGAS | ≥ 0.80 |
| 生成 | answer_relevancy | RAGAS | ≥ 0.85 |
| 安全 | 误拒率 | 自建 | ≤ 3% |
| 性能 | P95 延迟 | 自建 | ≤ 6s |

阈值是起点，**第一次 baseline 跑完再回头校准**。所有指标按 `scenario_tag` 切一份，定位最差的 3 个场景定向优化。

---

## 6. 目录结构与跑法

```
eval/
├── dataset/eval_set_v1.jsonl     # 150 条评估集
├── pipeline/
│   ├── runner.py                 # 灌入 RAGent，落 runs/*.jsonl（含运行时字段）
│   └── score.py                  # 编排指标计算
├── metrics/
│   ├── ragas_judge.py            # 5 个 RAGAS 指标
│   ├── intent.py                 # 意图准确率
│   ├── retrieval.py              # Doc Hit@K / Recall@K / MRR
│   ├── behavior.py               # 误拒率 / 过召回率
│   └── latency.py                # TTFT P50/P95/P99
├── report/
│   ├── markdown.py               # report.md + per_sample.csv + failures.jsonl
│   └── slides.py                 # 16:9 HTML PPT
└── reports/v1_<ts>/
    ├── report.md                 # 完整 markdown 报告
    ├── per_sample.csv            # 每条样本各指标分数
    ├── failures.jsonl            # 失败样例供人工 review
    └── slides.html               # 横向翻页 HTML
```

注：`tool_eval.py`（Tool Calling）在本版本未实现，评估集未带 `tool_calls_gold`。

**两步法**：

1. **录制**：runner 把 150 条灌进 RAGent，落一份 `runs/*.jsonl`。RAG 评测最大隐藏成本——**录一次评 N 次**。
2. **评分**：基于录制结果，RAGAS 与自建脚本并行跑。

---

## 7. 落地步骤

1. 录 baseline：评估集放 `eval/dataset/`，runner（`eval/pipeline/runner.py`）调 ragent SSE 接口，跑通 150 条。
2. 先跑自建（意图 / Doc Hit@K / Tool / 拒答 / 性能）——纯规则，秒级出结果，可挂 CI。
3. 再跑 RAGAS 5 个指标——验证 judge 稳定后放量。
4. 按 `scenario_tag` 分层，找最差 3 个场景定向迭代。
5. 后续每次改 prompt / 改召回 / 换模型，重跑评分，对比 baseline，禁止劣化合入。

---

## 8. 踩坑提示

- **Judge 同源偏置**：被评模型和 judge 同源会偏高估，必须换族。
- **RAGAS 方差**：单次差 5% 内别下结论，跑 3 次。
- **真值漂移**：前置项目升级文档版本时锁版本号，否则 `reference_doc_ids` 全错。
- **成本失控**：单条样本 5 个 RAGAS 指标约 **~15 次 judge 调用**（faithfulness 拆 claims 验证、context_precision 每 chunk 一次、context_recall 每 statement 一次、其余各 1-2 次）。150 条 × 15 × 3 轮取均值 ≈ **6750 次 LLM 调用 / 完整评测**，先在 10 条上验证再放量。
- **中文 NaN**：RAGAS 默认 prompt 拆 statement 时偶尔返回非 JSON。先设 `RunConfig(max_retries=3)` 兜底；若仍高频出现，再考虑自定义中文 prompt 覆盖默认模板（代价较大，能 retry 解决就别动）。
