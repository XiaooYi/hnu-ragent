# Docker 生产部署

本文档用于将“岳麓知枢”部署到一台 Ubuntu 24.04 CVM。该编排运行前端、Spring Boot 后端、MCP Server、PostgreSQL + pgvector、Redis、RocketMQ 和 RustFS。

## 1. 服务边界

公网只开放 Nginx 的 80 端口。PostgreSQL、Redis、RocketMQ、RustFS 和 MCP Server 均只在 Docker 内网可访问。

```text
Browser --80--> frontend (Nginx) --/api--> backend
                              └--/storage--> RustFS

backend --> PostgreSQL + pgvector / Redis / RocketMQ / RustFS / MCP Server
```

RustFS 控制台不开放公网端口。需要排查时使用 SSH 隧道访问。

## 2. 首次部署

在服务器中将仓库放到 `/opt/ragent` 后执行：

```bash
cd /opt/ragent
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
nano deploy/.env
```

必须填入数据库、Redis、RustFS、模型服务和 MinerU 的密钥。`RAGENT_PUBLIC_BASE_URL` 在只有 IP 时形如 `http://服务器公网IP/storage`；完成域名和 HTTPS 后应改为 `https://你的域名/storage`。

先拉取中间件镜像。不要直接执行不带服务名的 `docker compose pull`：它会尝试从镜像仓库拉取本地构建的 `ragent-backend:local`、`ragent-mcp-server:local` 和 `ragent-frontend:local`，而这些本地标签不在 Docker Hub，会返回 `denied`。

```bash
# 只拉取 PostgreSQL、Redis、RocketMQ、RustFS 等中间件镜像。
docker compose --env-file deploy/.env -f deploy/compose.yaml pull \
  postgres redis rmqnamesrv rmqbroker rustfs
```

构建项目镜像：

```bash
# 2 核服务器建议顺序构建，避免 backend 与 mcp-server 同时执行 Maven 编译。
# 首次构建会下载 Maven / npm 依赖，耗时约十几分钟属于正常现象。
docker compose --env-file deploy/.env -f deploy/compose.yaml build --pull backend
docker compose --env-file deploy/.env -f deploy/compose.yaml build --pull mcp-server
docker compose --env-file deploy/.env -f deploy/compose.yaml build --pull frontend
```

> `backend` 与 `mcp-server` 都需要 Maven 编译，且都会依赖 `framework` 模块。在 2 核 8 GiB 的服务器上并行构建会争抢 CPU 和网络，顺序构建通常更稳定。首次构建完成后，Docker 与 Maven 缓存会被复用，后续构建会明显更快。

启动全部核心服务：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

首次创建 PostgreSQL 数据卷时，会自动执行 `resources/database/schema_pg.sql` 和 `resources/database/init_data_pg.sql`。已有数据卷时不会再次执行初始化 SQL。

## 3. 验证与日志

```bash
curl -i http://127.0.0.1/healthz
docker compose --env-file deploy/.env -f deploy/compose.yaml logs -f backend
docker compose --env-file deploy/.env -f deploy/compose.yaml logs -f rustfs
docker compose --env-file deploy/.env -f deploy/compose.yaml logs -f rmqbroker
```

浏览器访问 `http://服务器公网IP/`。前端通过同源 `/api/ragent` 调用后端，避免浏览器跨域配置。

## 4. RocketMQ Dashboard（仅运维时启用）

Dashboard 默认不启动，也只绑定到服务器本地回环地址：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile ops up -d rocketmq-dashboard
```

本地电脑建立 SSH 隧道后访问：

```bash
ssh -L 8082:127.0.0.1:8082 ubuntu@服务器公网IP
```

然后在本地浏览器打开 `http://127.0.0.1:8082`。

## 5. 日常运维命令

```bash
# 查看服务状态
docker compose --env-file deploy/.env -f deploy/compose.yaml ps

# 重新构建并滚动替换应用镜像（2 核服务器顺序构建）
docker compose --env-file deploy/.env -f deploy/compose.yaml build backend
docker compose --env-file deploy/.env -f deploy/compose.yaml build mcp-server
docker compose --env-file deploy/.env -f deploy/compose.yaml build frontend
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --no-deps backend frontend mcp-server

# 停止服务但保留数据库、MQ、RustFS 数据卷
docker compose --env-file deploy/.env -f deploy/compose.yaml down

# 查看 Docker 数据卷占用
docker system df -v

# 导出 PostgreSQL 备份
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > ragent-postgres-$(date +%F).sql
```

不要执行 `docker compose down -v`，否则会删除 PostgreSQL、Redis、RocketMQ、RustFS 的命名数据卷。

## 6. 安全组

公网入站仅开放：

```text
TCP 80   0.0.0.0/0
TCP 443  0.0.0.0/0（配置 HTTPS 后）
TCP 22   仅你的固定公网 IP
```

不要开放 `5432`、`6379`、`9876`、`10909`、`10911`、`9000`、`9001`、`9090`、`9099`、`8082`。

## 7. 重要限制

- 当前 RustFS 镜像固定为项目本地已经验证过的 `1.0.0-alpha.72`。不要改用浮动 `latest` 标签。
- `deploy/.env` 保存密钥，已被 `.dockerignore` 排除；不要提交或上传到 GitHub。
- 后端构建阶段会将 `application.yaml` 中的本地密钥替换为运行时占位符，最终后端镜像不包含这些明文密钥。
- 这台 2 核 8 GiB CVM 适合当前小范围试用。批量导入 PDF 时请分批执行，并监控 CPU、内存和磁盘。
