# RAG 评测系列文章规划（比特严选 × Ragent）

> 写给对应 AI 知识问答 18 篇之后的 **评测系列**。
> 上一阶段的 18 篇讲清楚了"问答链路怎么跑通"，这一系列回答的是**另一个完全不同的问题**：
> "跑通之后，怎么证明它跑得好？改了一行 prompt，怎么知道是变好还是变差？"
>
> 受众与上一系列相同：有 Java/Python 开发基础、对 RAG 概念熟悉、但没系统做过 RAG 评测的工程师。
> 风格与规范沿用 `temp/CLAUDE.md`：中文标点、克制使用引号、PlantUML 图表、表格对比、Java/Python 代码示例。

---

## 设计思路

参考 18 篇问答的"地图 + 放大格子"的结构，但**评测项目的形状不一样**：

- 问答链路是**一条流水线**，18 篇是按阶段切的；
- 评测项目是**两个仓库 × 四段流程 × 两套指标**——
  - 仓库：`ragent`（被测）+ `ragenteval`（评测）
  - 流程：`init`（建知识库 / 灌文档 / 建意图树）→ `run`（跑评测集）→ `score`（算指标）→ `report`（出报告）
  - 指标：自建（秒级出结果，CI 友好）+ RAGAS（LLM-as-judge，慢但语义级）

因此结构按**"先地图，后两条主线（自建 / RAGAS），再收口"**来切：

```
评测系列
 │
 ├─ 总览（1 篇）
 │   └─ 1. RAG 系统怎么评？两仓库 × 四流程 × 两套指标
 │
 ├─ 评估集与初始化（2 篇）
 │   ├─ 2. 评估集是评测的"地基"：150 条到底怎么标
 │   └─ 3. 把评估集喂进 Ragent：知识库 / 文档 / 意图树的初始化
 │
 ├─ Runner 链路（1 篇）
 │   └─ 4. 一条 query 怎么跑：评测旁路接口 + SSE 聚合 + TTFT 打点
 │
 ├─ 自建指标（2 篇）
 │   ├─ 5. 意图准确率 + Hit@K / Recall@K / MRR：上游闸门与检索质量
 │   └─ 6. P95 看首字还是看整流？性能指标的口径选择
 │
 ├─ RAGAS（3 篇）
 │   ├─ 7. RAGAS 是什么？为什么只挑这 5 个指标
 │   ├─ 8. 把 RAGAS 跑起来：Python 环境 + 依赖安装 + 第一个分数
 │   └─ 9. RAGAS 5 个指标全解读：faithfulness / answer_relevancy / context_precision / context_recall / answer_correctness
 │
 └─ 报告与闭环（2 篇）
     ├─ 10. Judge 同源偏置、方差、中文 NaN：RAGAS 的三个坑
     └─ 11. 出报告：分层看板 + 失败样例 + PPT 风格 HTML

合计 11 篇。
```

每篇标题就是一个具体问题，正文给出答案，读者看完觉得"这一篇就解决了一个我关心的事"。

---

## 速览：每篇核心内容（不重复原则）

> **关键约束**：相同内容在不同文章里只出现一次，其它文章只放"上一篇说过 X，本篇基于此往下走"的一句话承接。
> 文末标注 **【独占内容】**，明确这篇文章相比其它文章新增了什么。

### 第 1 篇：RAG 系统怎么评？两仓库 × 四流程 × 两套指标

**核心问题**：调好了 prompt，换了 chunk size，到底是变好还是变差？没有指标盘只能靠手感。

**正文骨架**：
- 不评的代价：举三个真实场景（改 chunk 不知道好坏 / 换 embedding 模型靠感觉 / 改 prompt 出现回归）
- 评测的全景图：`init → run → score → report` 四段
- 两个仓库的职责切分：`ragent` 只暴露被评接口，`ragenteval` 负责评测集和指标
- 两套指标互补关系图：
  - 自建（秒级、纯运算、可挂 CI）→ 意图 / Hit@K / TTFT
  - RAGAS（LLM-as-judge、慢、有方差）→ faithfulness / answer_correctness / context_*
- 一张全景流程图（PlantUML）：`eval_set_v1.jsonl` → runner → `runs/*.jsonl` → score → `reports/<run>/`
- 一份阅读路径：按角色推荐（架构师看 1/4/10、研发看 5-6、QA 看 2/3/11）

**【独占内容】**：评测项目的全景拓扑图，后续每篇放大一个格子。明确**两个仓库的边界**、**三段流程的产物形状**、**两套指标的互补**。所有后续文章只解释"自己这一格"，不会再画全景图。

---

### 第 2 篇：评估集是评测的"地基"：150 条到底怎么标

**核心问题**：评测集长什么样、覆盖什么、标多少条够用？

**正文骨架**：
- 为什么不能用"线上日志抽样"起步：分布不均、ground truth 缺失、隐私
- 150 条的来源：3 个一级意图（SUPPORT / FEEDBACK / CHAT）+ 22 个二级意图，按业务比例分布
- 评估集 schema 详解：`query_id` / `query` / `intent_l1` / `intent_l2` / `difficulty` / `requires_rag` / `expected_doc_ids` / `ground_truth`
- `difficulty` 分层（easy / medium / hard）的标注口径
- `requires_rag` 字段为什么必须有：用来把"应该走 SYSTEM 兜底"和"应该走 RAG"的样本分开，避免污染检索指标
- 用 `trap_type` 标"难点类型"：多跳推理、跨文档对比、拒答陷阱
- **简提一下 must / nice 的分层**（一段话带过，不展开标注规范）：`expected_doc_ids` 是**最小核心证据集**（must）、`expected_doc_ids_nice` 是**可缺省的扩展证据**（nice）；对应 `Recall@K (must)` 和 `Recall@K (inclusive)` 两个口径。具体怎么标可查 `docs/03b_eval_labeling_convention.md`
- 起步规模建议（50~100 条够跑首版 baseline，150 条够分场景看分层）

**【独占内容】**：评估集的 schema 字段每个**为什么存在**、**会被哪个指标消费**、**缺失的副作用**。后续文章在引用某个字段（如 `requires_rag` / `expected_doc_ids_nice`）时只会一句话带过来源，不再重复定义。

---

### 第 3 篇：把评估集喂进 Ragent：知识库 / 文档 / 意图树的初始化

**核心问题**：评测之前，被测系统要长什么样？

**正文骨架**：
- 三步初始化（对应 `eval/init/`）：
  - `create_kbs.py`：建 4 个知识库（按文档类型切：product 商品库 / manual 使用手册库 / policy 政策库 / faq FAQ库）
  - `upload_docs.py`：批量上传 md 文件 + 触发分块（异步，要等 `chunkCount > 0`）
  - `build_intent_tree.py`：3 个 DOMAIN + 5 个 CATEGORY + 22 个 TOPIC，KB 归属由评估集 `expected_doc_ids` 投票多数派决定
- 三个本地映射文件的作用：`kb_ids.json`（KB 名 → ragent id）/ `doc_id_map.json`（业务码 → ragent doc id）/ `intent_ids.json`（intentCode → 节点 id）
- 为什么 `doc_id_map.json` 不能进 git：本地运行产物，换机器/换数据库 ID 全变
- 幂等性设计：基于映射文件跳过已上传，断点续传
- 异步分块的坑：`/chunk` 只是触发，全部上传完要等几分钟，调 `GET /knowledge-base/{id}/docs` 验证
- `reset_kbs.py` 重置脚本：默认 dry-run，破坏性操作要 `--yes`

**【独占内容】**：初始化阶段的产物 + 幂等 + 异步分块等待。所有后续文章默认这一步已完成，不会再讲怎么灌库。

---

### 第 4 篇：一条 query 怎么跑：评测旁路接口 + SSE 聚合 + TTFT 打点

**核心问题**：评测要拿到检索证据 + 真实答案 + 性能数据，runner 是怎么把这些一次性收齐的？

**正文骨架**：

**Part 1 ── 被测侧：评测旁路接口 `/rag/eval` 的设计**
- 直接调 `/rag/v3/chat` 的两个问题：拿不到中间产物（召回的 chunkId / 子问题 / 意图叶子）；SSE 流里抠数据慢
- `GET /rag/eval` 的设计取舍：
  - **只跑检索、不跑 LLM**——`EvalController` 直接组合 `QueryRewriteService → IntentResolver → RetrievalEngine`
  - JSON 同步返回 `EvalResponse`（docIds / chunkIds / contexts / intentLeafIds / hasKb / hasMcp）
  - `app.eval.enabled=true` 开关，生产关闭零开销
- `chunkId → docId` 两跳映射：`t_knowledge_chunk.doc_id` → `t_knowledge_document.doc_name` → 剥后缀 → 业务码
- 自承的设计妥协：评测旁路与生产链路走不同代码 → pipeline 内的检索改造（重排 / 过滤 / 合并）不会反映在旁路 → **漂移风险**

**Part 2 ── 评测侧：runner 的双接口聚合**
- 一条 query 跑两个接口：
  - `GET /rag/v3/chat` (SSE)：拿真实链路的 `response` / `thinking` / `final_status` / `first_token_ms`
  - `GET /rag/eval` (JSON)：拿检索证据
- 为什么自己写 SSE 解析（`parse_sse_stream`）不用 `requests.iter_lines()`：后者在事件分隔符 `\n\n` 跨 HTTP chunk 边界时会吞空行，丢中间事件
- **TTFT 打点的精确口径**：只算 `type=response` 且 `content` 非空的第一个 delta 到达——**不算 thinking 链路**，对应用户体感的"答案首字"
- 事件类型与 `final_status` 映射：`finish` → success / `reject` → refused / `cancel` → cancelled / 异常 → error
- 一次跑两个接口的代价：**两次独立检索**，召回结果不保证完全一致（受随机性 / 缓存 / 改写抖动影响）
- 并发控制：默认串行 + sleep 0.3s 避开 ragent 全局限流（`max-concurrent=10`），多线程 `--workers` 选项
- 产物 schema：`EvalRecord` dataclass，落 `runs/v1_<ts>.jsonl` 一行一条

**【独占内容】**：评测旁路接口设计 + Runner 侧 SSE 解析 + TTFT 打点口径 + 双接口聚合的妥协。后续文章在算 P95 / 读 response / 引用 `intent_pred` 时只会一句话带过来源。

---

### 第 5 篇：意图准确率 + Hit@K / Recall@K / MRR：上游闸门与检索质量

**核心问题**：意图错则全错——这个最上游的指标怎么算？检索好不好，怎么用纯集合运算判？

**正文骨架**：

**Part 1 ── 意图分类准确率（最上游闸门）**
- 为什么意图是"最上游闸门"：意图错 → 路由错 → 检索目标错 → 答案错。RAGAS 完全不评意图分类，必须自建
- 指标定义：**Top-1 Accuracy** = `intent_pred == intent_gold` 的比例
- 评估集的 `intent_l2` 和 ragent 返回的 `intentLeafIds[0]` 对齐方式
- 多子问题情况下取 `intent_pred = next((c for c in intent_codes if c), None)` —— 取第一个非空叶子作为预测
- 错分的两种典型模式：跨意图相近词（"3000 元手机" 在 S1_选购推荐 / S3_对比选购 之间漂移）/ 上下文歧义（"能退吗"）
- 分场景看：按 `intent_l2` 切片，找最差的 3 类定向优化

**Part 2 ── 检索文档级指标**
- 文档级指标 vs chunk 级指标：本项目用 doc 级，因为评估集标的是 doc，chunk 维度不易稳定标注
- 用一个具体例子串起三个指标（相关集 `{D3, D7, D9}`、Top-5 召回 `{D1, D3, D5, D7, D8}`）：
  - **Hit@K**：二值，Top-K 里只要有 1 个相关就算 1.0——最宽松
  - **Recall@K**：连续值 `命中数 / 总相关数 = 2/3 ≈ 0.67`——关注覆盖完整性
  - **MRR@10**：只看第一个相关文档的位置 `1/2 = 0.5`——关注排序质量
- K 怎么选：Hit@5 是主指标（≥ 90% 为参考目标），K=1/3/10 看排序质量分布
- 样本过滤：**仅统计 `requires_rag=true` 且 `reference_doc_ids` 非空** 的样本，SYSTEM 兜底类不污染检索指标
- `Recall@K (inclusive)` 把 nice 一起算的实现细节（呼应第 2 篇带过的 must/nice）
- 跟 RAGAS context_recall 双指标对照：Doc 命中但 RAGAS recall 低 → 召回到了但 chunk 切错
- 性能优势：纯集合运算，秒出，每次提交都能跑

**【独占内容】**：意图指标的算法 + 检索文档级三个指标的口径 + 样本过滤逻辑 + 与 RAGAS 的对照定位。其它文章引用"意图 Top-1 ≥ 92%"或 "Hit@5 / Recall@5"时直接给结论。

---

### 第 6 篇：P95 看首字还是看整流？性能指标的口径选择

**核心问题**：对话产品的"卡顿"到底是什么？

**正文骨架**：
- 整流耗时（latency_ms）的问题：随生成 token 数线性增长，长答案天然慢，**不反映卡顿**
- 用户体感卡点：**第一个 token 到达** —— TTFT（Time To First Token）
- TTFT 的精确口径（呼应第 4 篇）：第一个 `type=response` 且 `content` 非空的 delta 到达时打点；**不算 thinking 链路**
- 分位数为什么看 P95 不看均值：长尾掩盖在均值里，少数慢请求是真实用户痛点
- 同时报 P50 / P95 / P99 + 均值的目的：分布形态比单点更有信息量（均值贴近 P50 = 稳定；均值远高于 P50 = 长尾严重）
- TTFT 取不到怎么办：异常请求回退用 `latency_ms`（在 `metrics/latency.py` 实现）
- 整流 P95 只作参考：放在二级 KPI 里，不上看板
- 与生产监控的对齐：Ragent 侧的 trace 也应该按 TTFT 报警，评测和监控同口径

**【独占内容】**：性能指标的口径选择思路（为什么不看整流）+ 分位数对比读法 + TTFT 取不到的回退逻辑。

---

### 第 7 篇：RAGAS 是什么？为什么只挑这 5 个指标

**核心问题**：RAGAS 几十个指标，到底应该跑哪几个？

**正文骨架**：
- RAGAS 是什么：开源 RAG 评估框架（GitHub: explodinggradients/ragas），全靠 LLM-as-judge，把 RAG 的端到端答案拆成 claim / statement 再让 judge 评
- 为什么自建指标不够：自建只能算"命中没命中"，算不了"内容对不对"——比如召回的 chunk 都对，但模型胡编了一段没在 chunk 里的话，自建指标看不出来
- 为什么不能全跑：贵（单条 5 指标 ≈ 15 次 judge 调用，150 条 × 15 × 3 轮 ≈ 6750 次 LLM 调用 / 完整评测）
- 选 5 个的判断标准：**跑得起 + 看得懂 + 能驱动迭代**
- 5 个的功能分组：
  - 生成层：faithfulness（幻觉）/ answer_relevancy（切题）/ answer_correctness（正确）
  - 检索层：context_precision（精度）/ context_recall（覆盖）
- 为什么不要 `context_entity_recall` / `noise_sensitivity` / multi-turn 系列：本项目用不上 / 跑不起 / 信号噪比低
- 为什么不要 `answer_similarity`：`answer_correctness = 0.75 × claim F1 + 0.25 × similarity` 已经包了
- LLM-with-reference 变体 vs Non-LLM 变体：本项目统一用 LLM-with-reference（依赖 `reference` 标准答案，更准）
- 自建指标和 RAGAS 的协同：自建当 CI 闸门、RAGAS 当离线深度评估
- 一张选型对比表（5 个选中 vs 5 个落选的代表，对应"看什么 / 为什么不选"）

**【独占内容】**：RAGAS 的选型逻辑 + 不选某些指标的理由 + 与自建指标的协同关系。后续两篇直接展开"怎么装"和"5 个指标怎么算"，不再讲为什么选它们。

---

### 第 8 篇：把 RAGAS 跑起来：Python 环境 + 依赖安装 + 第一个分数

**核心问题**：选好指标了，怎么从零开始跑出第一个 RAGAS 分数？

**正文骨架**：

**Part 1 ── Python 环境**
- 为什么必须 Python 3.11：RAGAS 依赖链对 3.10 不友好（`pydantic-core` 编译报错），3.12 新（部分依赖未跟上）
- macOS / Linux / Windows 三种平台的 Python 3.11 安装：
  - macOS：`brew install python@3.11`，配 `alias python='/opt/homebrew/bin/python3.11'`
  - Linux：`apt install python3.11 python3.11-venv`
  - Windows：官网 installer，注意勾 "Add to PATH"
- 验证：`python --version` 应该是 3.11.x
- 虚拟环境（推荐）：`python -m venv .venv` + `source .venv/bin/activate`，避免污染全局

**Part 2 ── 依赖安装**
- 三个核心依赖：`ragas`（评估框架）/ `langchain-openai`（连 judge 模型）/ `datasets`（HuggingFace 的数据容器，RAGAS 输入格式）
- 完整安装命令：`pip install ragas langchain-openai datasets`
- 常见坑：
  - 安装慢 → 换镜像 `pip install -i https://pypi.tuna.tsinghua.edu.cn/simple ragas`
  - `pydantic` 版本冲突 → `pip install "pydantic>=2.0,<3.0"`
  - macOS M 芯片报 `tokenizers` 编译错误 → 升级 Xcode Command Line Tools
- 验证安装：
  ```python
  import ragas
  print(ragas.__version__)  # 应该输出 0.2.x
  ```

**Part 3 ── Judge 模型配置**
- 为什么 RAGAS 必须配 judge 模型：所有指标都靠 LLM 打分
- 用 OpenAI 兼容端点（如 aihubmix）的最小配置：
  ```bash
  export AIHUBMIX_API_KEY=<your_key>
  export AIHUBMIX_BASE_URL=https://aihubmix.com/v1
  export JUDGE_MODEL=gpt-5.4-mini
  export EMBEDDING_MODEL=qwen3-embedding-8b
  ```
- judge 模型为什么不直接用项目 chat 模型：同源偏置（第 10 篇会展开）
- embedding 模型为什么单独配：`answer_relevancy` 需要算余弦相似度，必须有 embedding

**Part 4 ── 跑第一个分数（5 条样本端到端 demo）**
- 准备最小数据集（一条 user_input + response + retrieved_contexts + reference）
- 完整可跑的 Python 脚本：
  ```python
  from ragas import evaluate
  from ragas.metrics import faithfulness, answer_correctness
  from datasets import Dataset
  
  ds = Dataset.from_dict({
      "user_input": ["..."],
      "response": ["..."],
      "retrieved_contexts": [["..."]],
      "reference": ["..."],
  })
  result = evaluate(ds, metrics=[faithfulness, answer_correctness])
  print(result.to_pandas())
  ```
- 跑成功的标志：终端输出 `evaluating with [faithfulness] ... 100%` + 一个 DataFrame 含 0~1 的分数
- 跑失败的常见原因：API key 错 / model 名错 / 网络超时（`RunConfig(timeout=180)`）
- 本项目的封装位置：`eval/metrics/ragas_judge.py`，跟教学 demo 的区别（多了样本过滤、`--ragas-n 3` 多轮取均值、按 intent 分层）

**【独占内容】**：Python 环境 + RAGAS 依赖安装 + 跑通第一个分数的完整 demo。后续文章默认 RAGAS 已经跑起来了，不会再讲怎么装。

---

### 第 9 篇：RAGAS 5 个指标全解读

**核心问题**：5 个 RAGAS 指标各自衡量什么、内部算法是什么、不达标怎么归因？

**正文骨架**：一张大对照表 + 五段算法骨架 + 三组配对解读。

**一张总表先建坐标系**：

| 指标 | 层 | 比较对象 | 算法骨架 | 不达标第一反应 |
|---|---|---|---|---|
| faithfulness | 生成 | response vs contexts | claim 拆分 → 让 judge 判每个 claim 能否由 contexts 推出 | prompt 没限定知识来源 / 上下文挤掉关键 chunk |
| answer_relevancy | 生成 | response vs user_input | 反向从 response 生成 N 个问题 → 与原问题算余弦相似度 | response 跑题 / 啰嗦冗余 / history 污染 |
| answer_correctness | 生成 | response vs reference | 0.75 × claim F1 + 0.25 × similarity | 端到端答案差，综合性指标 |
| context_precision | 检索 | contexts vs reference | 让 judge 对每个 chunk 判"对回答有用没用"（带位置权重） | rerank 没起作用 / topK 太大 |
| context_recall | 检索 | contexts vs reference | reference 拆 statement → 让 judge 判每个 statement 能否由 contexts 推出 | chunk 切分太细 / 路由策略错 / 召回漏 |

**五段算法骨架（每段一小节）**：

1. **faithfulness（忠实度，幻觉检测）**
   - 算法分解：response → atomic claims → 每个 claim 让 judge 判 supportable / not supportable → 计算 `supported / total`
   - 一个具体例子：response "iPhone 16 Pro 保修 1 年，AppleCare+ 延保 2 年"，contexts 里只有 "保修 1 年" → claim 1 supported、claim 2 not supported → faithfulness = 0.5

2. **answer_relevancy（答案相关性）**
   - 算法分解：让 judge 从 response 反向生成 N=3 个问题 → 每个问题与 user_input embedding 算余弦 → 取平均
   - **关键陷阱**：**只看切题，不看正确性** —— 答错了但切题也能拿高分
   - 必须和 answer_correctness 配套读，单看会误判

3. **context_precision（召回精度）**
   - 算法分解：对 retrieved_contexts 前 K 个 chunk，每个让 judge 判"对回答 user_input 有没有用"→ 加上位置衰减权重（前面的 chunk 权重更高）→ 求平均
   - 为什么带位置权重：召回顺序本身就是质量信号，第一名比第五名更重要

4. **context_recall（召回覆盖）**
   - 算法分解：把 reference 拆成 atomic statements → 每个 statement 让 judge 判"能不能由 contexts 推出来" → 计算 `covered / total`
   - 与 faithfulness 的对偶关系：faithfulness 拆 response 的 claim、recall 拆 reference 的 statement

5. **answer_correctness（端到端答案正确性）**
   - 算法分解：**`0.75 × claim F1 + 0.25 × similarity`**
     - claim F1：response 和 reference 都拆 claim → 算 TP（response 有 + reference 有）/ FP / FN → F1
     - similarity：embedding 后算余弦
   - 为什么 0.75 / 0.25：事实对比是硬指标、相似度防过严
   - 与 faithfulness 的本质区别：
     - faithfulness：response vs contexts（**召回支不支持答案**）
     - answer_correctness：response vs reference（**答案对不对**）
     - 一个答案可能 faithful 但不正确（召回里就是错的）、也可能正确但不 faithful（模型自己知道答案绕过了召回）
   - **本项目的特殊坑**：评估集里 145 条 `ground_truth` 仍是元指令格式（"应推荐..."、"应命中..."），不是真实自然语言答案 → 会让 `answer_correctness` 系统性偏低。解决思路：升级到 v1.2 标注规范，用 LLM 把元指令改写成自然语言

**三组配对读法**：
- **faithfulness + answer_relevancy**：低 faithful + 高 relevant = 编造但切题；高 faithful + 低 relevant = 没编造但跑偏
- **context_precision + context_recall**：高 P + 低 R = 召回挑剔但漏；低 P + 高 R = 召回全但脏；都低 = 检索整体崩
- **faithfulness + answer_correctness**：高 faithful + 低 correct = 召回本身错了；低 faithful + 高 correct = 模型靠预训练知识绕过了召回

**优化路径决策树（一张图收尾）**：
- 答案差 → 先看 answer_correctness → 决定从生成 or 检索找原因 → 进 faithfulness / context_recall 两条分支

**【独占内容】**：5 个 RAGAS 指标的算法 + 三组配对读法 + 优化路径决策树。其它文章引用某个 RAGAS 指标的分数时直接给结论。

---

### 第 10 篇：Judge 同源偏置、方差、中文 NaN、成本失控：RAGAS 的四个坑

**核心问题**：跑 RAGAS 一次出报表能信吗？

**正文骨架**：
- **坑一：Judge 同源偏置**
  - 现象：被评模型和 judge 同 provider 同族（如 gpt-5.4 + gpt-5.4-mini）会偏高估
  - 原因：同族模型对相同提示有共同的 "舒适区"，judge 倾向认可同族输出
  - 对策：换族（GPT 评非 GPT、Qwen 评非 Qwen），固定一个 GPT-4 级别 judge
  - 本项目当前的妥协：被评 gpt-5.4 + judge gpt-5.4-mini 同 provider 同族，**待修复**
- **坑二：单跑方差**
  - 现象：单次差 3~5% 不算退化，但单跑下结论会误判
  - 对策：`--ragas-n 3` 独立跑 3 次按样本取均值后再比较，不使用 OpenAI API 的 `n` 参数（一次请求多次采样不算独立）
  - 上线门槛：单次跑改造前后 ≥ 5% 差距才认定有效
- **坑三：中文 NaN**
  - 现象：RAGAS 默认 prompt 拆 statement 时偶发返回非 JSON（中文场景）
  - 原因：默认 prompt 是英文的，中文拆 claim 时大模型偶尔返回带说明文字的非纯 JSON
  - 对策：`RunConfig(max_retries=3, timeout=180)` 兜底，先重试解决；若仍高频再自定义中文 prompt 覆盖默认模板（代价大，能 retry 就别动）
- **坑四（附带）：成本失控**
  - 单条 5 指标 ≈ 15 次 judge 调用、150 条 × 3 轮 ≈ 6750 次
  - 对策：`--limit` 控批次大小，`--skip-ragas` CI 跳过
- 一份"跑 RAGAS 前的体检清单"

**【独占内容】**：RAGAS 的实操坑 + 防御策略。其它文章在用 RAGAS 结果时不再讲这些。

---

### 第 11 篇：出报告：分层看板 + 失败样例 + PPT 风格 HTML

**核心问题**：指标都算完了，怎么呈现给老板和 reviewer 看？

**正文骨架**：
- 三类受众，三种产物：
  - **CI / 自动化**：`reports/<run>/_scores.json` —— 给脚本读的中间产物
  - **研发 / Reviewer**：`report.md` + `per_sample.csv` + `failures.jsonl` —— 给人读的细节
  - **老板 / 周会**：`slides.html` —— 16:9 横向翻页，浏览器演示
- **一页纸看板设计**：意图 / 检索 / 生成 / 性能 四维 × 自建/RAGAS 双来源 × 参考目标列
- **分层报告**：
  - `by_intent_l1` 切片：找一级意图里最差的
  - `by_intent_l2` 切片：找二级意图里最差的 3 个，定向优化
- **失败样例归因**（`failures.jsonl`）：
  - 多原因合并：Hit@5 miss / answer_correctness < 0.5 / 误拒 / 过召回
  - 一条失败样本写明所有失败原因，方便人工 review
- **人工列设计**（`per_sample.csv`）：每个 RAGAS 指标自动补一个 `*_manual` 空列，人工填写后 `report` 重跑会按"人工列优先、空值回退 RAGAS"重算
- **PPT 渲染**：基于 `guizang-ppt-skill` 模板（瑞士国际主义 / 电子杂志风），封面 → 4 大 KPI → 次级 KPI → 分层 → 失败样例 → 收束
- **持续优化闭环**：评测 → 归因 → 改 prompt / 改召回 / 换模型 → 重跑 → 对比 baseline → **禁止劣化合入**

**【独占内容】**：报告产物形态 + 分层切法 + 失败归因 + 人工复核流程 + PPT 渲染。系列的收尾，串起前面所有指标的"看法"。

---

## 衔接关系速查

| 篇号 | 上承（一句话） | 下启（一句话） |
|---|---|---|
| 1 | — | 评估集是评测的地基，下一篇拆它 |
| 2 | 上一篇画了全景，本篇从地基讲起 | 评估集准备好了，下一步灌入 Ragent |
| 3 | 评估集就位，被测系统要长什么样 | 系统建好了，怎么把 query 喂进去拿数据 |
| 4 | 系统建好了，怎么把 query 跑一遍拿到原始数据 | runner 跑完数据收齐了，开始算自建指标 |
| 5 | 数据有了，开始算指标，先讲意图和检索 | 自建指标讲完意图和检索，剩下性能 |
| 6 | 自建指标全讲完，接下来是 LLM-as-judge | RAGAS 是什么，先选型 |
| 7 | RAGAS 选了 5 个，但要先装环境才能跑 | 环境装好，看 5 个指标的算法 |
| 8 | RAGAS 跑起来了，5 个指标各自怎么算 | 指标算法讲完了，但坑还没说 |
| 9 | 5 个指标讲完，开始踩坑 | 坑都过了，开始出报告 |
| 10 | 跑 RAGAS 的实操坑 | 报告怎么呈现 |
| 11 | 闭环 | — |

---

## 速览图

```
RAG 评测系列
 │
 ├─ 1.  全景图：两仓库 × 四流程 × 两套指标             ← 地图
 │
 ├─ 2.  评估集 schema 与 150 条分布                    ┐ 评估集
 ├─ 3.  KB / 文档 / 意图树初始化                       ┘ 与初始化
 │
 ├─ 4.  评测旁路接口 + SSE 聚合 + TTFT 打点            ← 链路
 │
 ├─ 5.  意图准确率 + Hit@K / Recall@K / MRR            ┐ 自建指标
 ├─ 6.  P95 看首字而非整流                             ┘
 │
 ├─ 7.  RAGAS 是什么 + 为什么选这 5 个                 ┐
 ├─ 8.  装 Python + RAGAS + 跑通第一个分数             ├ RAGAS
 ├─ 9.  5 个指标全解读（算法 + 配对读法）              ┘
 │
 ├─ 10. RAGAS 的四个坑                                 ┐ 落地
 └─ 11. 报告 + 看板 + 失败归因                         ┘

11 篇，每篇聚焦一个具体问题。标题是问题，正文是答案。
```

---

## 写作约束（与 temp/CLAUDE.md 对齐）

1. **语言风格**：中文口语化、用"你""咱们"拉近距离、技术术语首次出现给中文解释
2. **章节结构**：每篇以"为什么需要 → 怎么做 → 怎么选/有什么坑"三段
3. **格式**：标题用 `##` 起步、对比用表格、流程图用 PlantUML（瑞士风主题）、引用块标重要补充
4. **标点**：中文标点，禁止英文逗号句号；技术术语用反引号、不用英文引号
5. **代码**：评测项目用 Python 3.11、Ragent 侧用 Java 17，**完整可运行**，给运行输出
6. **图表**：流程图 / 时序图 / 漏斗图都用 PlantUML 嵌入，统一蓝（#1565C0 + #E3F2FD）/ 绿（#C8E6C9 + #2E7D32）/ 暖黄（#FFF9C4 + #F9A825）主题
7. **每篇有承上启下**：开头一句话承接上一篇，结尾一句话预告下一篇
8. **每篇有"独占内容"声明**：写作时自查"这一篇有什么是其它文章不会再讲的"

---

## 与 Chat 18 篇的差异

| 维度 | Chat 18 篇 | 评测 11 篇 |
|---|---|---|
| 主题形状 | 一条流水线，按阶段切 | 两仓库 × 三流程 × 两指标体系，按维度切 |
| 受众目标 | "我要做一个 RAG 链路" | "我要证明我的 RAG 链路跑得好" |
| 代码主语 | Java（Ragent 后端） | Python（评测项目）+ Java（被评接口） |
| 章节边界 | 每篇一个阶段 / 一个组件 | 每篇一个指标 / 一段流程 |
| 收尾 | 队列限流是基础设施收口 | 报告 + 闭环是评测的收口 |
| 不会重复的 | 全景图、prompt 模板、消息装配顺序 | 全景图、评估集 schema、TTFT 口径、RAGAS 算法 |

---

## 下一步建议

1. 先按这份大纲产出**第 1 篇（总览）**，验证语气、长度、PlantUML 风格是否合预期
2. 第 1 篇定稿后，按 2 → 3 顺序写评估集 / 初始化（这两篇耦合最紧，一次写完）
3. 自建指标 2 篇（5-6）可以并行准备，每篇独立性强
4. RAGAS 3 篇（7-8-9）建议先把 RAGAS 跑过一次实测拿到真实分数后再写，避免空谈；其中第 8 篇的安装教程要在干净的虚拟机或新虚拟环境里实测一遍
5. 第 10 / 11 篇放最后，需要前面 9 篇的结论作为引用素材
