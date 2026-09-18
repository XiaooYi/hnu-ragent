# Ragent 评测体系文档总结与面试问答

> 基于 9 份粘贴文档整理，并结合当前 Ragent 源码实现。核心口径：这些材料讲的不是“RAG 怎么做 demo”，而是“RAG 做出来后，如何证明它真的变好了，以及如何定位变差的环节”。

## 一、文档内容总结

这批文档围绕 Ragent 的 RAG 评测体系展开，主线可以概括成：

```text
评估集设计
  -> 测评数据初始化
  -> 单次问答双接口采集
  -> 自建指标计算
  -> RAGAS 离线深度评估
  -> 性能指标口径
  -> 报告产物与持续优化
```

### 1. 为什么要做评测体系

文档反复强调：RAG 系统跑通不等于效果好。修改 chunk size、embedding 模型、rerank、system prompt 后，如果没有固定评估集和指标，就只能靠抽查几个 case 判断，风险很大。

典型风险：

- 改 chunk size 后，少数 case 看起来正常，但售后类问题集体退化。
- 换 embedding 后，中文语义变好，但中英文型号检索变差。
- 调 prompt 后幻觉少了，但拒答率飙升。

所以评测体系的目标是：每次改动都能回答“哪里变好了、哪里变差了、差在哪一层”。

### 2. 评测体系为什么拆成两个仓库

文档里把系统拆成两个角色：

- `ragent`：被评系统，Java 17 + Spring Boot，提供 `/rag/v3/chat` 和 `/rag/eval`。
- `ragenteval`：评测项目，Python 3.11，负责评估集、runner、指标脚本、RAGAS 和报告。

这么拆的原因：

- 评测逻辑不侵入生产代码。
- Python 生态更适合 RAGAS、pandas、报告生成。
- 被评系统和评测系统可以独立迭代。

### 3. 评估集是地基

文档强调：评估集没建好，RAG 评测都是白搭。

评估集不是从线上日志随便抽 50 条，因为线上日志有三个问题：

- 分布不均，高频问题太多，冷门意图覆盖不到。
- 没有 ground truth，不知道应该命中哪个文档、哪个意图、怎么回答。
- 有隐私合规风险，可能包含订单号、手机号、用户名。

因此评估集要专门设计。文档里的完整集是 150 条，主力快跑集是 20 条。字段包括：

- `query`：真实口语化用户问题。
- `intent_l1` / `intent_l2`：两级意图标签。
- `requires_rag`：是否应该走 RAG。
- `expected_doc_ids`：必须命中的标准文档。
- `expected_doc_ids_nice`：命中更好但非必须的文档。
- `ground_truth`：标准参考答案。
- `difficulty` / `trap_type`：难度和陷阱类型，用于失败归因。
- `expected_answer_type` / `eval_metrics`：答案类型和参与指标。

### 4. 初始化数据为什么重要

文档里提到，评估集标了应该召回某个文档，但如果 Ragent 知识库里没有这篇文档，检索指标全是 0，这不是系统差，而是环境没有初始化好。

初始化流程包括：

- 建 4 个知识库：商品库、使用手册库、政策库、FAQ 库。
- 上传并分块 115 篇 Markdown 文档。
- 构建 3 个一级意图、22 个二级意图叶子节点。
- 建立意图节点和知识库之间的关联。

为什么按文档类型建 4 个库，而不是按 22 个意图建 22 个库？

因为很多意图共享同一批文档。比如售后政策、退换货、发票、物流都可能查政策库；配网、操作指南都查手册库。按意图建库会导致重复灌入和管理成本上升。

### 5. 为什么一条 query 要跑两个接口

生产接口 `/rag/v3/chat` 是 SSE 流式输出，能拿到最终回答和 TTFT，但拿不到中间产物，比如召回了哪些 chunk、命中了哪些 intent。

评测接口 `/rag/eval` 是旁路接口，只跑：

```text
Query Rewrite -> IntentResolver -> RetrievalEngine
```

不跑最终 LLM 生成，返回：

- `retrievedDocIds`
- `retrievedChunkIds`
- `retrievedContexts`
- `retrievedContextDocIds`
- `intentLeafIds`
- `latencyMs`
- `hasKb` / `hasMcp`

所以 runner 的设计是一条 query 跑两个接口：

```text
/rag/v3/chat：拿真实回答、SSE delta、TTFT、总耗时
/rag/eval：拿检索证据、意图结果、chunk/doc 映射
```

最后合并成一条 `EvalRecord` 写入 `runs/*.jsonl`。

### 6. 自建指标负责 CI 闸门

文档里的自建指标不依赖 LLM，适合每次提交快速跑：

- `intent_top1`：意图 Top-1 准确率。
- `Hit@K`：Top-K 里是否至少命中 1 篇相关文档。
- `Recall@K`：Top-K 里覆盖了多少必须文档。
- `MRR@10`：第一篇相关文档排在第几位。
- `TTFT P50 / mean`：用户首字等待时间。

自建指标的特点：

- 纯集合运算，快。
- 稳定，不受 judge 模型随机性影响。
- 适合做 CI 阈值拦截。

### 7. RAGAS 负责离线深度语义评估

RAGAS 评的是端到端语义质量，文档重点解释了 5 个指标：

- `faithfulness`：回答是否有上下文支撑，主要看幻觉。
- `answer_relevancy`：回答是否切题。
- `answer_correctness`：回答和 reference 是否事实一致。
- `context_precision`：召回上下文里有多少有用内容，且是否排在前面。
- `context_recall`：标准答案需要的信息是否被上下文覆盖。

RAGAS 的定位不是 CI 快速闸门，而是离线深度评估。原因：

- 慢，需要 LLM-as-Judge。
- 贵，每条样本多个指标、多次 judge 调用。
- 有随机方差，单次结果不能过度解读。
- 中文场景可能出现 NaN 或格式失败。

### 8. 性能指标为什么看 TTFT

流式问答里，用户体感最关键的是 TTFT（Time To First Token），不是整流耗时。

整流耗时会受答案长度影响，回答 50 字和 500 字总耗时差很多，但用户看到首字的等待可能差不多。TTFT 覆盖的是：

```text
请求进入 -> 记忆加载 -> Query Rewrite -> 意图识别 -> 检索 -> rerank -> LLM 首个 response token
```

文档强调：TTFT 只算 `type=response` 的首个非空 delta，不算 thinking。

小样本下不建议主看 P95/P99，因为 20 条样本时 P95 基本就是倒数第一或倒数第二，极易受异常值影响。文档建议看 `P50 + mean`。

### 9. 报告产物服务不同角色

文档把报告产物拆成多种：

- `_scores.json`：给 CI 和自动化脚本读。
- `report.md`：给研发和 reviewer 看一页纸总览。
- `per_sample.csv`：逐样本明细，方便人工复核。
- `failures.jsonl`：失败样本归因。
- `slides.html`：给技术负责人或汇报场景看。

核心思想：同一套指标数据，要用不同形态服务不同受众。

## 二、面试高频问题与项目化答案

## 1. 你这个 RAG 项目为什么要单独做评测体系？

因为 RAG 的效果不是靠几个 case 看出来的。RAG 链路很长，任何一个改动都可能局部变好、其他场景变差，比如换 embedding、调 chunk size、改 prompt、开 rerank。没有评测体系，就不知道这次改动到底提升了什么、退化了什么。

我在项目里把评测拆成两类：

- 自建指标：意图准确率、Hit@K、Recall@K、MRR、TTFT，适合 CI 快速检查。
- RAGAS 指标：faithfulness、answer_relevancy、answer_correctness、context_precision、context_recall，适合离线语义评估。

> 一句话总结：
>
> faithfulness：有没有根据上下文说话
> answer_relevancy：有没有回答用户问的问题
> answer_correctness：和标准答案比，对不对、全不全
>
> context_precision：召回结果准不准，噪声多不多
> context_recall：关键证据全不全，漏没漏
> 面里可以这样说：
>
> faithfulness 解决幻觉问题，判断回答是否被检索上下文支撑；
>
> answer_relevancy 解决跑题问题，判断回答是否围绕用户问题；
>
> answer_correctness 解决最终准确性问题，判断回答和标准答案是否一致。
>
> context_precision 更关注检索结果的纯度和排序质量，低了说明无关 chunk 太多或者 rerank 不好；context_recall 更关注覆盖率，低了说明标准答案需要的信息没被召回，可能是 query rewrite、embedding、chunk 切分或知识库内容有问题。

这样既能快速发现召回和路由问题，也能评估最终回答是否忠实、切题、正确。

## 2. 为什么不能直接从线上日志随机抽 query 做评估集？

不能直接抽，主要有三个问题。

第一，分布不均。线上大部分问题集中在少数高频意图，冷门但高风险的场景覆盖不到，比如退换货、故障排查、跨品类对比。

第二，没有标准答案。日志里只有用户问题和系统回答，没有 `expected_doc_ids`、`ground_truth` 和标准意图，Hit@K、Recall@K、answer_correctness 都没法算。

第三，有合规风险。线上 query 可能包含订单号、手机号、地址等敏感信息，直接拿去给 judge 模型评分不合适。

所以我的评估集是专门设计的，覆盖不同意图、难度、陷阱类型，并且每条都有标准文档、标准答案和是否需要 RAG 的标注。

## 3. 你的评估集怎么设计？有哪些字段？

评估集不是只有 query，而是“用户问题 + 标注信息”。核心字段包括：

- `query`：口语化用户问题。
- `intent_l1` / `intent_l2`：一级和二级意图。
- `requires_rag`：这条问题是否应该走知识库检索。
- `expected_doc_ids`：必须召回的文档。
- `expected_doc_ids_nice`：命中更好但非必须的文档。
- `ground_truth`：标准答案。
- `difficulty`：难度分层。
- `trap_type`：陷阱类型，比如预算约束、跨品类对比、缺少关键信息。
- `expected_answer_type`：答案类型，比如 recommendation、policy、comparison。
- `eval_metrics`：这条样本参与哪些指标。

我会重点强调 `requires_rag`，它是分流字段。比如“今天天气怎么样”不应该走 RAG，如果把它也算进检索指标，会污染 Hit@K 和 Recall@K。

```json
{"query_id": "S9-01", "query": "扫地机怎么连 WiFi？", "intent_l1": "SUPPORT", "intent_l2": "S9_配网连接", "difficulty": "easy", "requires_rag": true, "expected_answer_type": "network_steps", "expected_doc_ids": ["NET_GUIDE_001", "MANUAL_VAC_001"], "trap_type": "basic_pairing", "ground_truth": "配网步骤：1) 打开米家 APP 或 Roborock APP；2) 点击右上角「+」选择「添加设备」，等待 APP 自动发现或手动搜索扫地机型号；3) 按 APP 提示长按主机重置键，等待指示灯闪烁或语音提示进入待连接状态；4) 选择家庭 2.4GHz WiFi（扫地机不支持 5GHz），输入 WiFi 密码；5) 等待绑定完成后为设备命名并选择房间。配网前确认：手机已开启蓝牙、定位、本地网络权限，且手机本身连接的是 2.4GHz WiFi 而非 5GHz。常见失败原因：手机连到 5GHz、密码错误、路由器双频合一导致设备无法发现。", "eval_metrics": ["recall@3", "recall@5"]}
{"query_id": "S8-01", "query": "扫地机首次使用，如何开机？", "intent_l1": "SUPPORT", "intent_l2": "S8_操作指引", "difficulty": "easy", "requires_rag": true, "expected_answer_type": "steps", "expected_doc_ids": ["MANUAL_VAC_001"], "trap_type": "basic_operation", "ground_truth": "长按主机电源键，指示灯亮起后即完成开机。首次使用前建议将主机放回充电座或基站充满电（T7 约 6 小时，T7S Plus 约 3 小时，G10 / G10S Pro 以基站回充显示为准）。", "eval_metrics": ["recall@3", "recall@5"]}

```



## 4. 为什么要有 `requires_rag` 字段？

因为不是所有问题都应该检索知识库。

如果 `requires_rag=false` 的问题也参与检索指标，比如闲聊、越界问题、天气问题，系统检索不到是正常的，但指标会被错误拉低。

反过来，如果 `requires_rag=true` 的问题被系统拒答了，就属于误拒。比如用户问“手机电池能用多久”，这应该查商品文档，系统如果说“找不到信息”，就要记为问题。

所以 `requires_rag` 有三个作用：

- 过滤检索指标样本。
- 计算误拒率。
- 计算过召回率，也就是不该检索却检索了。

## 5. 为什么用 150 条全量评估集，又用 20 条主力评估集？

150 条用于覆盖完整意图体系和边界场景，适合做阶段性回归和版本评估。

20 条主力集用于日常快速验证。因为 RAGAS 成本高，一条样本可能涉及多个指标、多次 LLM judge 调用。如果 150 条全部跑 3 轮，成本和耗时都比较高。

所以策略是：

```text
20 条主力集：高频跑，覆盖广，反馈快
150 条全量集：低频跑，做版本回归和完整质量评估
```

面试时可以说：我不会只用 20 条证明整体质量，20 条只是快速闸门；完整结论要看 150 条全量集。

## 6. 为什么初始化成 4 个知识库，而不是 22 个意图一个库？

因为知识库应该按文档类型或业务资料来源组织，而不是机械按意图拆。

项目里是 4 个库：

- 商品库：商品详情、参数、选购指南。
- 使用手册库：操作说明、配网、App 使用。
- 政策库：保修、退换货、物流、发票、会员政策。
- FAQ 库：故障排查、错误码、常见问题。

如果按 22 个二级意图建 22 个库，会导致很多文档重复灌入。例如退换货和售后政策都要查政策文档，操作指南和配网连接都要查手册文档。按文档类型建库，再通过意图树把意图关联到对应 KB，更灵活也更可维护。

## 7. `/rag/v3/chat` 和 `/rag/eval` 为什么都需要？

因为两个接口承担不同目标。

`/rag/v3/chat` 是生产接口，SSE 流式输出，能拿到真实回答、首字时间、总耗时，但不会暴露完整中间产物。

`/rag/eval` 是评测旁路接口，只跑 query rewrite、intent resolve、retrieval，不跑 LLM 生成。它返回召回文档、chunk、上下文、意图叶子节点等证据。

runner 一条 query 跑两个接口：

```text
/rag/v3/chat -> response、TTFT、latency
/rag/eval    -> retrievedDocIds、retrievedChunkIds、retrievedContexts、intentLeafIds
```

然后合并成 EvalRecord，才能同时算生成指标、检索指标、意图指标和性能指标。

## 8. `/rag/eval` 只跑检索不跑 LLM，会不会和真实链路有偏差？

会有一点偏差，这就是旁路接口的代价。

当前 `/rag/eval` 复用了生产链路的核心能力：QueryRewriteService、IntentResolver、RetrievalEngine，所以检索证据和意图结果基本对齐。但它不经过最终 Prompt 组装和 LLM 生成，因此不能单独评估最终答案质量。

所以评测设计上用双接口聚合：

- `/rag/eval` 负责中间证据。
- `/rag/v3/chat` 负责真实生成结果。

面试时可以补一句：旁路接口必须尽量复用生产代码，不能另写一套检索逻辑，否则评测结果会失真。

## 9. 为什么评测用文档级 docId，而不是 chunk 级 chunkId？

因为 chunkId 不稳定。只要调整 chunk size、overlap、分块算法，同一段内容的 chunkId 就可能变化，旧评估集全部失效。

文档级 docId 更稳定。评估集标注的是 `expected_doc_ids`，比如 `POLICY_RETURN_002`，系统召回 chunk 后再映射回业务文档 ID。

源码里 `/rag/eval` 的映射链路是：

```text
RetrievedChunk.id
  -> t_knowledge_chunk.docId
  -> t_knowledge_document.doc_name
  -> 去掉文件后缀，得到业务 docId
```

这样既能用 chunk 做检索，又能用稳定的文档 ID 做评估。

## 10. 意图 Top-1 Accuracy 怎么算？为什么 RAGAS 不够？

意图 Top-1 Accuracy 就是系统最有信心的第一个意图是否等于人工标注的 `intent_l2`。

公式很简单：

```text
intent_top1 = 预测 Top1 意图正确的样本数 / 参与评估的样本数
```

RAGAS 不评意图。RAGAS 只看上下文和回答，比如 faithfulness、answer_correctness、context_precision，它不关心你路由是否走对。一个问题意图错了，但碰巧检索到相关片段，RAGAS 分数可能还不错；但从系统设计看，路由已经错了。

所以意图指标必须自建，而且要放在 RAG 链路最前面看。

## 11. 多子问题拆分后，意图 Top-1 怎么算？

如果一个 query 被拆成多个子问题，每个子问题都可能有一个 Top-1 意图。评测时要定义清楚口径。

常见做法有两种：

- 严格口径：所有关键子问题的意图都命中才算对。
- 主意图口径：取系统输出的主意图或最高分意图，与样本标注的主意图比较。

当前 Ragent 的 `/rag/eval` 会返回 `intentLeafIds`，它和子问题同序。评测脚本可以据此对每个子问题做比对，也可以取第一个或最高置信意图算 Top-1。

> 我们当前意图 Top-1 用的是主意图口径。这么做的原因是：子问题拆分主要是为了提升检索覆盖，不是为了把一条样本变成多意图评测。当前评估集大部分也是单轮单意图问题，多子问题场景很少，所以取第一个非空意图作为主意图，口径简单、稳定、可复现。

## 12. Hit@K、Recall@K、MRR 分别解决什么问题？

这三个都是检索指标，但关注点不同。

`Hit@K` 问的是：Top-K 里有没有至少一篇相关文档。

```text
Hit@K = 1，如果 Top-K ∩ 标准相关文档集 非空
Hit@K = 0，否则
```

它衡量底线召回能力。

`Recall@K` 问的是：标准相关文档找全了多少。

```text
Recall@K = |Top-K ∩ 标准相关文档集| / |标准相关文档集|
```

它衡量覆盖完整性。

`MRR@10` 问的是：第一篇相关文档排在第几位。

```text
MRR = 1 / 第一篇相关文档排名
```

它衡量排序质量。相关文档排第 1，MRR 是 1；排第 5，MRR 是 0.2。

## 13. `must` 和 `nice` 两档 Recall 有什么意义？

不是所有相关文档重要性都一样。

`expected_doc_ids` 是 must 文档，缺了会影响答案正确性。比如退货政策里的退货时限、申请流程，是必须召回的。

`expected_doc_ids_nice` 是 nice 文档，命中会让答案更完整，但不是核心证据。比如商品推荐问题里，参数文档是 must，评测文章或用户指南可能是 nice。

这样设计可以避免指标过于粗糙：

- must recall 看核心证据有没有召回。
- nice recall 看答案丰富度和补充资料覆盖。

## 14. RAGAS 的 5 个指标分别怎么解释？

`faithfulness`：回答里的事实是否能被 contexts 支撑。低说明模型可能幻觉，或者用了上下文外的知识。

`answer_relevancy`：回答是否切题。RAGAS 会从回答反向生成问题，再和原问题比较语义相似度。

`answer_correctness`：最终答案是否正确。它通常结合 claim F1 和 embedding similarity，既看事实点是否对齐，也容忍表述差异。

`context_precision`：召回的 contexts 里有多少是有用的，并且有用内容是否排在前面。低说明噪声 chunk 多，rerank 或过滤有问题。

`context_recall`：标准答案需要的信息是否被 contexts 覆盖。低说明检索漏召回，或者知识库缺文档。

一句话：

```text
context_precision / context_recall 看检索上下文
faithfulness / answer_relevancy / answer_correctness 看生成结果
```

## 15. faithfulness 和 answer_correctness 有什么区别？

`faithfulness` 看回答是否忠实于检索上下文。它不要求回答一定和标准答案一致，只要求回答的内容能从 contexts 推出来。

`answer_correctness` 看回答是否和 reference 标准答案一致。

举例：

- contexts 本身召回错了，模型忠实地根据错误 contexts 回答，faithfulness 可能高，但 answer_correctness 低。
- contexts 里没有某个信息，模型靠常识补出来且答案碰巧正确，answer_correctness 可能高，但 faithfulness 低。

所以两个指标要一起看。RAG 场景里，不能只追求答案看起来对，还要能被上下文支撑。

## 16. context_precision 和 context_recall 怎么定位问题？

`context_precision` 低，说明召回结果里噪声多，常见原因是：

- TopK 太大。
- rerank 没生效。
- 向量召回相似但不相关。
- 去重和过滤不够。

`context_recall` 低，说明标准答案需要的信息没找全，常见原因是：

- query rewrite 不够好。
- chunk 切得太碎或太粗。
- embedding 模型召回能力不足。
- 知识库缺文档。
- 意图路由查错库。

如果 precision 高、recall 低，说明找来的内容比较干净，但漏信息；如果 precision 低、recall 高，说明信息找到了，但噪声也很多。

## 17. RAGAS 分数有波动怎么办？

RAGAS 是 LLM-as-Judge，天然有随机性。文档里提到单次跑可能有 3%-5% 方差，所以不能因为一次从 0.72 变 0.69 就直接判断退化。

处理方式：

- 固定 judge 模型和 prompt。
- 关键版本跑多轮取均值。
- 对失败样本做人工复核。
- 对 NaN 或格式失败样本单独标记，不直接吞掉。
- 自建指标和 RAGAS 分开看，自建指标负责稳定闸门，RAGAS 负责深度分析。

面试时可以说：RAGAS 不是绝对真理，它是辅助判断，最终还要结合人工抽检和业务指标。

## 18. 为什么性能指标看 TTFT，而不是总耗时？

因为 RAG 是流式输出，用户最在意的是多久看到第一个答案字符，而不是整段答案多久完全输出。

总耗时和答案长度强相关。回答 500 字肯定比 50 字耗时长，但用户体感可能差不多，因为首字时间相近。

TTFT 覆盖了首字前的所有前置链路：

```text
记忆加载 -> Query Rewrite -> 意图识别 -> 检索 -> rerank -> LLM 首包
```

Ragent 源码里也有 `user-first-packet` trace 节点，用于记录从 pipeline 入口到第一个内容推给前端的用户感知首包时间。

## 19. 为什么小样本下不建议看 P95/P99？

因为样本太少时，P95/P99 基本等价于看最慢的一两条。

比如 20 条样本，P95 接近第 19 或第 20 条，极易被一次网络抖动、模型波动、冷启动影响。它不能稳定代表系统性能。

所以文档建议小样本看：

- `P50`：典型用户体验。
- `mean`：整体平均水平，能暴露少量慢请求拉高的问题。

大规模线上监控再看 P95/P99 更合理。

## 20. TTFT 的精确采集口径是什么？

只算 `type=response` 的首个非空 delta 到达时间，不算 thinking。

原因是 thinking 对用户通常不可见，不构成用户看到答案的时间点。如果模型先输出 thinking，再过几秒才输出 response，那么 TTFT 应该记录 response 首字，而不是 thinking 首字。

如果拿不到 `first_token_ms`，可以回退到总耗时，但要注意这只是兼容旧数据，不是理想口径。

## 21. RAGAS 为什么需要两个模型？

RAGAS 通常需要：

- judge LLM：用于拆 claim、判断 supportable、判断回答是否正确。
- embedding 模型：用于计算回答和问题、回答和 reference 的语义相似度。

例如 answer_relevancy 需要从回答反向生成问题，再算和原问题的 embedding 相似度。answer_correctness 也会结合事实 F1 和 embedding similarity。

所以 RAGAS 不是简单字符串匹配，它依赖 LLM judge 和 embedding。

## 22. RAGAS 挂了会不会影响自建指标？

不应该影响。

文档里的设计是 score 阶段先算自建指标，再跑 RAGAS。RAGAS 如果因为 API key、网络、judge 模型返回格式异常失败，自建指标仍然应该落盘。

这是合理设计，因为自建指标便宜、稳定、适合 CI；RAGAS 慢且不稳定，不能让它影响整套评测产物。

## 23. 报告为什么要拆成 `_scores.json`、`report.md`、`per_sample.csv`、`failures.jsonl`？

因为不同角色看的东西不一样。

CI 只需要 `_scores.json`，判断核心指标有没有跌破阈值。

研发需要 `report.md` 和 `per_sample.csv`，看哪个意图、哪个样本、哪个环节失败。

质量复盘需要 `failures.jsonl`，看失败归因，比如 intent_miss、retrieval_miss、context_noise、generation_hallucination。

负责人汇报需要可视化页面或 slides，看 overall 数字和趋势。

所以报告不是一个文件解决所有问题，而是同一份数据多种消费形态。

## 24. failures.jsonl 里应该怎么做失败归因？

可以按 RAG 链路分层归因：

- `intent_miss`：意图 Top-1 错。
- `retrieval_miss`：must 文档没召回。
- `rank_bad`：召回到了但排得太靠后。
- `context_noise`：无关 chunk 太多，context_precision 低。
- `context_missing`：标准答案信息没覆盖，context_recall 低。
- `generation_hallucination`：上下文没有支撑但模型编了。
- `answer_incomplete`：答案没错但漏关键事实。
- `wrong_refusal`：requires_rag=true 却拒答。
- `over_retrieval`：requires_rag=false 却去检索并回答。

一个样本可以有多个原因。比如意图错导致查错库，进而 retrieval miss，最后 answer incorrect。这种要保留完整故障链路，而不是只贴一个标签。

## 25. 简历里写 Recall@1 提升、Top-1 提升，面试官问怎么测，你怎么答？

我会这样答：

这些指标不是线上主观感受，而是在固定评估集上做 ablation 得到的。评估集包含 150 条样本，覆盖 3 个一级意图、22 个二级意图、115 篇知识库文档。

对比方式是固定评估集、固定知识库、固定模型配置，然后跑不同版本：

```text
baseline：原始 query + 全局向量检索
版本 A：术语归一化 + query rewrite
版本 B：加入意图定向检索
版本 C：加入双路召回 + 去重 + rerank
```

指标口径：

- `Recall@1`：Top-1 召回文档是否命中 must 文档。
- `Top-1`：可以指意图 Top-1，也可以指召回 Top-1，简历里要说清楚。
- `上下文精准度`：进入 Prompt 的上下文中相关内容比例，可以用 RAGAS context_precision 或人工标注计算。

面试时要补充：我不会只报 overall，还会看 by_intent_l2，因为 overall 涨了不代表所有意图都涨，可能某些冷门意图退化。

## 26. 如果调大 chunk size，怎么判断是变好还是变差？

不能只抽几个问题看，要跑固定评估集。

我会看：

- `Hit@K`：是否还能召回相关文档。
- `Recall@K`：核心证据是否找全。
- `context_precision`：chunk 变大后是否引入更多噪声。
- `context_recall`：chunk 变大后是否覆盖更多标准答案信息。
- `answer_correctness`：最终答案是否更完整。
- TTFT 和 token 成本：大 chunk 会增加 Prompt 长度和生成成本。

如果 Recall 提升但 context_precision 下降，说明大 chunk 带来了更多信息，也带来了噪声，需要配合 rerank、相邻 chunk 合并或 token budget 控制。

## 27. 如果换 embedding 模型，怎么做评估？

换 embedding 不能只看几条 case。我要重点看：

- 全量 Recall@K 和 MRR 是否提升。
- 按意图分层是否有退化。
- 中英文混合型号、数字、专有名词场景是否退化。
- 召回延迟和向量维度带来的存储成本。
- 历史向量是否需要重建。

不同 embedding 模型的向量空间不兼容，不能新旧向量混在一起查。正确做法是新建向量空间，重新 embedding，灰度对比后切换。

## 28. 如果改 system prompt 降低幻觉，怎么防止误拒率上升？

要同时看 faithfulness 和业务回答率。

只看 faithfulness 可能会误导。Prompt 约束越强，模型越容易拒答，幻觉少了，但用户该得到的答案也拿不到。

所以要看：

- faithfulness 是否提升。
- answer_correctness 是否提升。
- requires_rag=true 的样本中，错误拒答率是否上升。
- answer_relevancy 是否下降。
- 用户问题是否本来就能从 contexts 回答。

面试可以说：Prompt 不能只追求“少说错”，还要保证“该答的时候能答”。

## 29. 你怎么用评测报告指导下一轮优化？

我会按分层指标定位。

如果 `intent_top1` 低，优先看意图树、意图描述、易混淆边界、低置信澄清。

如果 `Hit@K` / `Recall@K` 低，优先看 query rewrite、embedding、切块、知识库是否缺文档。

如果 `MRR` 低，说明召回到了但排序差，优先看 rerank、融合排序、TopK。

如果 `context_precision` 低，说明噪声多，优先看过滤、去重、rerank 和 token budget。

如果 `faithfulness` 低，说明模型不够受上下文约束，优先看 Prompt、引用约束、低置信拒答。

如果 `answer_correctness` 低但检索指标高，说明检索到了但生成没用好，需要优化 Prompt 或回答模板。

## 30. 这套评测体系和线上 trace 有什么关系？

评测体系用于离线和版本回归，trace 用于线上单次请求排障。

评测体系回答的是：

```text
这个版本整体有没有变好？
哪个意图退化了？
哪个指标跌了？
```

Trace 回答的是：

```text
这一次请求慢在哪里？
这一次回答错在重写、意图、检索、rerank 还是生成？
```

当前 Ragent 源码里有 `@RagTraceNode`、`RagTraceAspect`、`StreamChatTraceRunner`，并且记录了 `user-first-packet` 作为用户感知 TTFT 节点。

理想状态是把评测和 trace 结合：评测发现某类样本退化后，用 trace/debug 接口看具体链路。

## 三、结合 Ragent 项目的面试总答法

如果面试官让你整体介绍这套评测体系，可以这样说：

> 我这个项目不是只做了 RAG 问答链路，还单独设计了一套评测闭环。生产接口 `/rag/v3/chat` 负责真实 SSE 回答，评测旁路 `/rag/eval` 复用生产的 query rewrite、intent resolver 和 retrieval engine，返回召回文档、chunk、上下文和意图结果。评测侧 runner 对同一条 query 同时调用两个接口，合并成 EvalRecord。后续 score 阶段先算不依赖 LLM 的自建指标，比如 intent_top1、Hit@K、Recall@K、MRR、TTFT，再离线跑 RAGAS 的 5 个语义指标。最后生成 `_scores.json`、`report.md`、`per_sample.csv`、`failures.jsonl` 等报告，用于 CI 闸门、研发排查和版本复盘。

更短版本：

> 我的评测体系核心是分层归因。意图错了看 intent_top1，检索漏了看 Hit@K/Recall@K，排序差看 MRR，噪声多看 context_precision，信息缺失看 context_recall，生成幻觉看 faithfulness，最终答案质量看 answer_correctness。这样每次改 query rewrite、embedding、rerank、prompt，都能知道到底是哪一层变好了或变差了。

## 四、最容易被追问的风险点

### 1. `/rag/eval` 是旁路，会不会和生产链路漂移？

会有风险，所以必须复用生产核心服务，而不是另写逻辑。当前项目里 `/rag/eval` 调的是 `QueryRewriteService`、`IntentResolver`、`RetrievalEngine`，和生产主链路一致；但它不覆盖 Prompt 组装和最终生成，所以还要结合 `/rag/v3/chat` 的真实 response。

### 2. RAGAS 能不能作为唯一评测标准？

不能。RAGAS 慢、贵、有 judge 方差，而且不评意图路由。它适合离线深度评估，不适合单独做 CI 闸门。CI 更适合用 intent_top1、Hit@K、Recall@K、MRR 这种确定性指标。

### 3. 为什么文档级召回更适合评估？

因为 chunk 会随切块策略变化而变化，文档级 ID 稳定。评测集标文档，系统内部仍然按 chunk 检索，再映射回 docId。

### 4. 怎么证明不是只对 20 条样本过拟合？

20 条是主力快跑集，不是全部结论。完整评估要看 150 条全量集，并且按意图、难度、trap_type 分层看。还可以定期更新评估集，加入线上匿名化后的失败样本。

### 5. 指标提升怎么避免“只涨 overall，局部退化”？

报告必须有 overall、by_intent_l1、by_intent_l2、per_sample。只看 overall 不够，必须看细分意图和失败样本。

## 五、背诵版总结

Ragent 的评测体系可以概括为一句话：

```text
用固定评估集保证可比性，用双接口 runner 拿齐答案和证据，用自建指标做稳定闸门，用 RAGAS 做语义深评，用报告和失败归因驱动下一轮优化。
```

面试时重点讲清楚三件事：

- 评估集怎么设计：150 条、12 个字段、覆盖意图、检索、标准答案、难度和陷阱。
- 指标怎么分层：意图、检索、上下文、生成、性能分别看不同指标。
- 如何落到项目：`/rag/v3/chat` 拿真实回答，`/rag/eval` 拿中间证据，runner 合并，score/report 输出结果。

