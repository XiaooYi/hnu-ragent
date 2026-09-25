# 多通道检索不变量

本规则约束知识库检索从候选产生到最终上下文的顺序。实现位置是 `bootstrap` 模块中的 `RetrievalEngine` 和 `MultiChannelRetrievalEngine`；详细架构见 [多通道检索说明](../architecture/multi-channel-retrieval.md)。

## 固定执行顺序

1. `RetrievalEngine` 以子问题为单位协调知识库与 MCP 检索。
2. `MultiChannelRetrievalEngine.executeSearchChannels()` 并行执行已启用的 `SearchChannel`。
3. 聚合后的候选按 `SearchResultPostProcessor` 的 `getOrder()` 顺序串行处理。
4. `DeduplicationPostProcessor` 的顺序为 `1`，始终执行，先于任何重排或截断。
5. `FusionPostProcessor` 的顺序为 `5`，仅在 `rag.search.fusion.strategy=rrf` 时执行；它负责跨模态融合排序与候选池截断，不改变去重结果集合。
5. `RerankPostProcessor` 的顺序为 `10`，仅在 `rag.rerank.enabled=true` 时执行；它是唯一负责 TopK 截断的后处理器。

因此，关闭重排时，所有已合并且去重后的候选都会继续向下游传递，**不会**有隐式 TopK 截断。任何新增截断、排序或过滤行为必须以新的后处理器明确表达其顺序、开关、观测指标和测试。

### 融合（RRF）的固定语义

- 融合只在**启用通道数大于 1** 时重排；单通道保持各通道原始召回顺序，仅做候选池截断。
- 融合分数使用倒数名次 `Σ 1/(k + rank)`，`k` 取 `rag.search.fusion.rrf-k`（默认 60）；向量余弦分与关键词 BM25 分量纲不同，**禁止**直接线性加权。
- 名次必须取自各通道返回的 `SearchChannelResult.chunks` 原始顺序，不能用去重后的合并列表，否则「多路命中」信息会丢失。
- `rag.search.fusion.rerank-candidate-limit` 控制送入 Rerank 的候选上限（默认 50，`<=0` 不截断）；这是 Rerank 之前的粗排截断，与 `RerankPostProcessor` 的最终 TopK 截断职责不同，两者不可互相替代。

## 通道启用规则

- `IntentDirectedSearchChannel` 仅在 `rag.search.channels.intent-directed.enabled=true` 且知识库意图得分不低于 `min-intent-score` 时启用。当前默认 `min-intent-score=0.4`。
- `VectorGlobalSearchChannel` 在自身启用，并且意图定向检索未启用、最大意图得分低于 `confidence-threshold`，或仅有一个中等置信度意图时启用。当前默认 `confidence-threshold=0.6`。
- `KeywordSearchChannel`（优先级 `5`）只在 `rag.keyword.type=es` 时存在，并且需要 `rag.search.channels.keyword.enabled=true` 才参与召回；它的检索范围由 `rag.search.channels.keyword.mode` 决定：`global` 取全部有效知识库、`intent` 只取意图命中的知识库、`both` 优先意图命中的知识库并在无命中时回退全库。
- 关键词通道与向量全局通道的「全库范围」都必须来自 `KbCollectionProvider.listActiveCollections()`（未删除知识库的 collection），不得使用索引名通配，避免命中已删除库的残留数据。
- 两个通道分别使用其配置的 `top-k-multiplier` 扩展候选池；该扩大不等同于最终 TopK。

阈值、乘数或启用条件变更时，必须同步修改 `bootstrap/src/main/resources/application.yaml`、本规则以及覆盖高/中/低意图置信度和重排开关的测试。

## 扩展约束

- 新通道实现 `SearchChannel`，声明确定的优先级与启用条件，不能在 Controller 或 Pipeline 内硬编码分支。
- 新后处理器实现 `SearchResultPostProcessor`，声明稳定顺序，并说明它与去重、重排和 TopK 的相对位置。
- 后处理器不得修改输入集合以外的会话或持久化状态，除非该副作用被单独设计、记录和测试。
- 每个候选结果应保留可用于 Trace 和排障的来源信息；不要为了简化显示而丢失通道来源。
