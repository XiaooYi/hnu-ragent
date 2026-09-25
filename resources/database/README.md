# PostgreSQL 数据库脚本

## 全量初始化

- `schema_pg.sql`：最新版本的完整表结构
- `init_data_pg.sql`：最新版本的初始化数据

新环境按顺序执行这两个文件即可，**不需要**再执行历史升级脚本。

## 增量升级

`upgrades/` 按正式发布版本划分目录，目录内脚本使用「三位顺序号_变更含义」命名：

```
upgrades/
├── v1.1.0/
│   ├── 001_knowledge_chunk_log_duration.sql
│   └── 002_message_thinking.sql
└── <下一版本>/
```

已部署实例必须按顺序号**逐个执行**，不要把多个脚本合并成一个过程脚本再执行。

当前已登记的升级脚本：

| 顺序 | 脚本 | 变更 |
| --- | --- | --- |
| v1.1.0-001 | `upgrades/v1.1.0/001_knowledge_chunk_log_duration.sql` | 分块日志表拆分计时字段（`embed_duration` / `persist_duration`） |
| v1.1.0-002 | `upgrades/v1.1.0/002_message_thinking.sql` | `t_message` 新增 `thinking_content` / `thinking_duration` |
| v1.1.0-003 | `upgrades/v1.1.0/003_intent_multi_collections.sql` | `t_intent_node` 新增 `collection_names` 并回填旧单值字段 |
| v1.1.0-004 | `upgrades/v1.1.0/004_message_sources.sql` | `t_message` 新增 `sources`（回答来源） |
| v1.1.0-005 | `upgrades/v1.1.0/005_message_recommendations.sql` | `t_message` 新增 `reply_to_message_id` / `grounding_chunks` / `recommended_questions` / `message_status` |

## 维护约定

1. 新增变更时先在 `upgrades/<目标版本>/` 下追加**新的**顺序号脚本，不修改已对外提供或被他人执行过的脚本。
2. 同时把最终表结构同步进 `schema_pg.sql`；只有确有必要时才更新 `init_data_pg.sql`。
3. 脚本应尽量幂等、可审计，包含必要的数据回填、索引与约束处理；破坏性变更必须先给出备份与回滚方案。
4. `backups/` 只存放历史备份或兼容材料，不作为新变更的入口。
