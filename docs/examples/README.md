# Ragent 真实实现索引

这里替代通用模板中的孤立 Demo。开发新能力时应从项目内的真实实现学习边界和写法，再抽取可复用模式；不要复制与 Ragent 包名、配置、数据模型不一致的样例代码。

| 需求 | 优先参考位置 | 说明 |
| --- | --- | --- |
| Spring Boot 启动与模块装配 | `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java` | Ragent 的应用入口 |
| 统一 HTTP 异常 | `framework/src/main/java/com/nageoffer/ai/ragent/framework/web/GlobalExceptionHandler.java` | 已有统一异常边界 |
| 配置绑定 | `infra-ai/.../config/AIModelProperties.java`、`bootstrap/.../rag/config/` | 使用 `@ConfigurationProperties` 绑定 `ai.*`、`rag.*` |
| 检索扩展 | `bootstrap/.../rag/core/retrieve/channel/` 与 `postprocessor/` | `SearchChannel` 和 `SearchResultPostProcessor` 的真实扩展点 |
| 模型接入 | `infra-ai/.../chat/ChatClient.java`、Embedding/Rerank 路由实现 | 客户端接口、候选优先级、熔断和降级 |
| Redis 与分布式协调 | `framework/.../idempotent/`、`bootstrap/.../rag/service/ratelimit/` | StringRedisTemplate、Lua 与 Redisson 的实际职责边界 |
| 文档处理 | `bootstrap/.../core/`、`bootstrap/.../ingestion/` | 解析、分块、向量化和节点扩展 |
| 前端接口与测试 | `frontend/src/`、`frontend/tests/`、`frontend/TESTING.md` | 现有组件、页面和 Node 测试方式 |

路径表中的 `...` 表示对应的 Java 包路径。选择示例前先阅读它的测试和调用方，确认其失败语义、配置前提与模块依赖均适用于当前需求。

## 接口示例

- [PDF 摄取示例](pdf/pdf-ingestion-example.md)：创建流水线、上传文件、查询任务和节点执行记录。
