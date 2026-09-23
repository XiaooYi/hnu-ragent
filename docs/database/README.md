# Ragent 数据库脚本约定

Ragent 当前不使用模板中的 Flyway `docs/database/migrations/` 目录。数据库 SQL 的权威位置是 `resources/database/`，Docker Compose 会在**首次**创建 PostgreSQL 数据卷时执行初始化脚本。

## 文件职责

| 文件或目录 | 用途 |
| --- | --- |
| `resources/database/schema_pg.sql` | 全新 PostgreSQL + pgvector 环境的完整表结构与必要索引 |
| `resources/database/init_data_pg.sql` | 全新环境的初始数据 |
| `resources/database/upgrade_vX_to_vY.sql` | 已部署实例按版本升级时执行的增量、可审计脚本 |
| `resources/database/backups/` | 历史备份或兼容材料，不作为新变更入口 |

`deploy/compose.yaml` 挂载 `schema_pg.sql` 与 `init_data_pg.sql` 到 PostgreSQL 初始化目录。数据卷已经存在时，PostgreSQL 不会重复执行它们；禁止为“补上变更”而随意删除数据卷、重跑完整 schema 或直接改生产库。

## 变更流程

1. 先检查相关实体、Mapper、查询、索引和现有升级脚本，明确 PostgreSQL 与 pgvector 的兼容性。
2. 新建由当前版本到目标版本的 `upgrade_vX_to_vY.sql`，脚本需能审计、尽可能幂等，并包含必要的数据回填、索引或约束处理。
3. 新部署所需的最终表结构同步更新 `schema_pg.sql`；只有确有必要时才更新 `init_data_pg.sql`。
4. 为关键查询、事务边界、数据迁移后行为或向量索引变更增加匹配测试，并在测试环境验证升级路径。
5. 更新关联文档和部署说明；生产执行前准备备份、回滚方案和维护窗口。

禁止将真实数据、密钥、生产地址或不可逆删除语句伪装成初始化脚本提交。需要破坏性变更时，必须先获得明确的运维方案和数据迁移计划。
