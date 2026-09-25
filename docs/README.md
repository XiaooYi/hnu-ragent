# Ragent 文档地图

Ragent 的文档按 AI dev template 的“上下文、规则、数据、示例、验收”方式组织。实现前先阅读根目录 `CONTEXT.md` 与 `AGENTS.md`，再按变更范围进入下列目录。

| 目录 | 内容 | 适用场景 |
| --- | --- | --- |
| [`architecture/`](architecture/) | 模块边界、RAG 管道、检索架构 | 理解系统、修改核心流程或扩展点 |
| [`development/`](development/) | 开发规范、实现待办和工程流程 | 编码、重构、提交或新增处理能力 |
| [`operations/`](operations/) | 本地启动、Docker 生产部署、自动发布 | 恢复环境、发布和排障 |
| [`domain/`](domain/) | 企业内部 MCP 与业务工具设计 | 增加工具或定义业务系统集成 |
| [`evaluation/`](evaluation/) | 评测、RAG 知识和面试问答材料 | 设计评测、解释指标或准备技术答辩 |
| [`rules/`](rules/) | 不可随意改变的行为不变量 | 修改阈值、顺序、状态或失败语义 |
| [`database/`](database/) | 数据库初始化、升级和迁移约定 | 修改表结构、索引或数据脚本 |
| [`examples/`](examples/) | 可运行或可核对的接口与摄取示例 | 调试文档摄取和接口调用 |
| [`releases/`](releases/) | 发版记录 | 查阅版本变更 |
| [`upstream/`](upstream/) | 与上游 `nageoffer/ragent` 的分叉判定、功能差异与落地路线 | 对齐上游能力、补齐缺失功能 |
| [`assets/`](assets/) | 文档图示和编辑资源 | 架构图、部署图和 README 媒体 |
| [`archive/`](archive/) | 已合并或仅保留历史的文档 | 追溯旧方案，不作为当前实现依据 |

## 当前权威入口

- RAG 请求顺序、模块职责和扩展点：[`architecture/ragent-architecture.md`](architecture/ragent-architecture.md)
- 多通道检索实现与配置：[`architecture/multi-channel-retrieval.md`](architecture/multi-channel-retrieval.md)
- 检索不变量：[`rules/retrieval-invariants.md`](rules/retrieval-invariants.md)
- 数据库脚本：[`database/README.md`](database/README.md)
- 文档摄取示例：[`examples/pdf/pdf-ingestion-example.md`](examples/pdf/pdf-ingestion-example.md)
- 上游对齐入口：[`upstream/README.md`](upstream/README.md)（分叉点 [`upstream/fork-divergence.md`](upstream/fork-divergence.md)、差异清单 [`upstream/feature-gap.md`](upstream/feature-gap.md)、路线图 [`upstream/roadmap.md`](upstream/roadmap.md)）

`archive/` 中的文档可能包含历史包路径、旧配置或旧结论。需要实现当前功能时，应以 `architecture/`、`rules/`、代码和测试为准；如果历史内容仍然有效，应先迁回正式文档并更新验证依据。
