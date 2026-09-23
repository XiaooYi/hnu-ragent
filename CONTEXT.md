# Ragent AI 开发上下文

本文件将 `ai-dev-template` 的通用约束落到 Ragent。开始任何需求、修复或重构前，先阅读本文件、`AGENTS.md`，以及与改动范围相应的 `docs/`、配置和现有测试；不要把通用模板中的示例表、示例类或虚构部署配置直接复制进 Ragent。

## 产品与边界

Ragent 是企业知识库与智能问答的 RAG / Agent 平台。它覆盖文档上传、异步解析、分块、向量化、检索、模型调用和 SSE 流式回答，并提供意图路由、MCP 工具调用、限流与链路追踪。

- 后端入口模块是 `bootstrap`，启动类为 `RagentApplication`；默认 HTTP 上下文为 `/api/ragent`。
- `framework` 承载异常、上下文、缓存、幂等、分布式 ID 和通用约定；`infra-ai` 承载 Chat、Embedding、Rerank 客户端、模型路由与熔断；`mcp-server` 是独立进程。
- 依赖方向只能是 `bootstrap -> framework + infra-ai`；`framework` 与 `infra-ai` 不得互相依赖。业务实现不要绕过模块边界。
- 前端位于 `frontend`，采用 React、TypeScript 与 Vite；接口访问沿用现有 API 层和页面组织，页面中不要新建零散的请求实现。

## 修改前的阅读路径

| 变更类型 | 必读内容 |
| --- | --- |
| 聊天、检索、意图或重排 | `AGENTS.md`、`docs/architecture/multi-channel-retrieval.md`、`docs/rules/retrieval-invariants.md`、相关 `bootstrap` 测试 |
| 文档上传、解析、分块、向量化 | `docs/architecture/ragent-architecture.md`、相应 ingestion/core 代码和测试 |
| 模型供应商、Embedding、Rerank | `infra-ai` 中的接口与路由实现、`bootstrap/src/main/resources/application.yaml` |
| 数据库 | `docs/database/README.md`、`resources/database/` 中现有脚本、对应实体和 Mapper |
| 部署、配置、中间件 | `deploy/compose.yaml`、`deploy/.env.example`、`docs/operations/project-startup-guide.md` |
| 前端 | `frontend/TESTING.md`、关联页面/组件/测试以及后端接口契约 |

## RAG 链路与不可破坏的边界

主聊天接口 `GET /rag/v3/chat` 经过 `RAGChatController`、`RAGChatServiceImpl` 和 `StreamChatPipeline.execute()`：加载会话记忆、查询改写与拆分、意图解析、澄清或纯系统回答短路、知识库/MCP 检索、提示词组装和流式生成。

检索必须沿用 `RetrievalEngine -> MultiChannelRetrievalEngine` 的两阶段模型：先并行执行检索通道，再按顺序执行后处理器。详细不变量见 [检索规则](docs/rules/retrieval-invariants.md)。新增能力优先通过既有 Spring Bean 扩展点接入，而非在 Pipeline 或 Controller 中写分支：

- 新检索通道实现 `SearchChannel`；
- 新后处理器实现 `SearchResultPostProcessor` 并定义稳定的顺序；
- 新 MCP 工具实现 `MCPToolExecutor`；
- 新文档处理节点实现 `IngestionNode`；
- 新模型供应商实现 `infra-ai` 的客户端接口，并接入候选路由。

## 配置、数据与安全

- 主应用配置在 `bootstrap/src/main/resources/application.yaml`。业务开关用 `@ConfigurationProperties` 绑定；不要在业务代码散落 `@Value`、系统属性读取或静态配置 Holder。
- RAG 配置以 `rag.*` 为根，模型路由以 `ai.*` 为根。变更阈值、TopK、模型维度或路由优先级时，必须同步更新规则文档和相应测试。
- 数据库初始化与升级脚本位于 `resources/database/`，不是模板中的 `docs/database/migrations/`；具体约定见 [数据库说明](docs/database/README.md)。
- 生产部署以 `deploy/compose.yaml` 和未提交的 `deploy/.env` 为准；以 `deploy/.env.example` 作为变量清单。不得提交密码、API Key、Token、私钥、生产 IP 或新的本机绝对路径。
- Redis 仅用于缓存、协调、幂等、限流和短生命周期状态。Key 需有明确的业务前缀、TTL 与失败策略；分布式锁、信号量和全局限流复用 Redisson。
- 日志必须参数化，禁止输出用户敏感内容、认证信息、完整提示词中的机密数据或模型密钥。

## 实现与验证方式

1. 先将需求落到可验证行为；涉及检索策略、阈值、状态或数据口径时先更新 `docs/rules/`。
2. 对功能或缺陷，先添加一个会失败的、覆盖真实行为的测试，再做最小实现；配置与文档类改动至少执行对应验证。
3. 后端遵循 `controller/request`、`controller/vo`、`service/bo`、`dto`、`core`、`dao/entity`、`dao/mapper`、`config` 的既有职责划分。Controller 不直接访问 Mapper，领域逻辑不依赖 HTTP 类型。
4. 前端使用函数组件和 Hooks；加载、错误和空态必须与现有页面体验保持一致。
5. 根据影响范围运行测试、Maven 构建或前端构建；再执行 `node test/validate-ai-dev-template.mjs`，确保 AI 开发模板仍与仓库入口一致。

提交信息采用 Conventional Commits：`<type>(<scope>): 中文说明`。小改动完成必要检查即可；影响检索、数据、部署、模型路由或跨模块边界的改动必须执行匹配的测试或构建。
