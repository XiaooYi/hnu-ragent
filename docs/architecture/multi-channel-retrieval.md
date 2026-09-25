# 多通道检索架构

本文档是当前 Ragent 多通道检索的权威说明。它对应 `bootstrap` 中的实际实现；历史重构记录保存在 `docs/archive/`，不作为当前代码契约。

## 适用范围

`RetrievalEngine` 按子问题协调知识库与 MCP 检索。知识库部分委托给 `MultiChannelRetrievalEngine`，后者固定分为两个阶段：

```text
Stage A: executeSearchChannels()
  ├─ IntentDirectedSearchChannel (priority=1)
  └─ VectorGlobalSearchChannel    (priority=10)
       ↓ 并行聚合
Stage B: executePostProcessors()
  ├─ DeduplicationPostProcessor (order=1)
  └─ RerankPostProcessor        (order=10, 受 rag.rerank.enabled 控制)
```

主聊天链路的上游顺序是：会话记忆 → 查询改写与子问题拆分 → 意图解析 → 澄清/系统短路 → 检索 → Prompt 组装与 LLM 流式生成。不要在 Controller 中绕过 `StreamChatPipeline` 直接拼装检索结果。

## 当前实现

### 检索通道

| 通道 | 优先级 | 启用条件 | 实际检索 |
| --- | ---: | --- | --- |
| `IntentDirectedSearchChannel` | 1 | 配置开启，且至少一个 KB 意图分数达到 `min-intent-score` | 在意图关联的知识库/Collection 中并行检索 |
| `VectorGlobalSearchChannel` | 10 | 配置开启，且意图定向关闭、最高意图分数低于 `confidence-threshold`，或只有一个中等置信度意图 | 在所有可用 Collection 中并行检索 |

当前默认值在 `bootstrap/src/main/resources/application.yaml`：`min-intent-score=0.4`、`confidence-threshold=0.6`、意图通道 `top-k-multiplier=2`、全局通道 `top-k-multiplier=3`。

通道优先级用于稳定排序和去重时的结果优先级；它不是最终答案排序，也不代表最终 TopK。

### 后处理器

- `DeduplicationPostProcessor` 固定 `order=1`，始终启用；它在聚合候选后按现有结果标识去重。
- `RerankPostProcessor` 固定 `order=10`，仅当 `rag.rerank.enabled=true` 时启用；它调用 `RerankService.rerank(query, candidates, topK)`，同时完成最终 TopK 截断。
- 当 `rag.rerank.enabled=false` 时，去重后的全部候选继续向下游传递，不能在通道或其他隐式位置再次截断。

处理器由 Spring 自动注入到 `MultiChannelRetrievalEngine`，按 `getOrder()` 升序执行。新增过滤、分数归一化或版本选择逻辑时，必须实现 `SearchResultPostProcessor`，明确顺序、开关、候选来源和测试，而不是修改引擎中的条件分支。

## 配置入口

所有配置使用 `bootstrap/src/main/resources/application.yaml`，不是独立的 `application-search.yml`：

```yaml
rag:
  vector:
    type: pg                 # pg 或 milvus
  rerank:
    enabled: true
  search:
    channels:
      vector-global:
        confidence-threshold: 0.6
        top-k-multiplier: 3
      intent-directed:
        enabled: true
        min-intent-score: 0.4
        top-k-multiplier: 2
```

修改这些阈值或倍数时，必须同步更新 [`rules/retrieval-invariants.md`](../rules/retrieval-invariants.md)、`test/test-cases.md` 和覆盖高/中/低置信度的自动化测试。

## 扩展方式

### 新增检索通道

实现 `SearchChannel` 并注册为 Spring Bean。实现必须提供稳定的名称、类型、优先级、启用条件和 `SearchChannelResult`，使用 `SearchContext` 的问题、意图和 TopK 信息。不要在 `RAGChatController`、`StreamChatPipeline` 或 `RetrievalEngine` 中写新通道专用分支。

### 新增后处理器

实现 `SearchResultPostProcessor` 并注册为 Spring Bean。`getOrder()` 要明确相对去重与重排的位置；如果会改变候选数量，必须说明是否承担 TopK 责任，避免产生第二个隐式截断点。

### 新增模型或 MCP 能力

模型供应商接入 `infra-ai` 的 Chat/Embedding/Rerank 客户端和候选路由；MCP 工具实现 `McpToolExecutor`，由注册表自动发现。检索链路只消费抽象接口，不直接依赖供应商 SDK 或 HTTP 细节。

## 验证清单

- 高置信度意图只执行预期的定向通道；低置信度或中等置信度场景按规则触发全局通道。
- 多通道结果先合并去重，再按启用状态执行重排；重排关闭时没有隐式 TopK。
- 通道或后处理器异常有明确的失败/降级语义，并能在 Trace 或日志中定位来源。
- 新增 Bean 不破坏模块依赖、SSE 流式取消、全局限流和现有 `bootstrap` 测试。
