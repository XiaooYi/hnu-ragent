<p align="center">
  <img src="assets/ragent-ai-banner.png" alt="Ragent" />
</p>

# Ragent

Ragent 是一个面向企业知识库与智能问答场景的 RAG / Agent 平台，提供从文档上传、异步解析、分块与向量化，到检索、模型生成和流式回答的完整链路。

项目同时提供管理端和后端服务，支持知识库管理、意图路由、多通道检索、模型路由、MCP 工具调用和链路追踪，便于在业务系统中构建可维护、可观测的智能问答能力。

## 核心能力

- 知识库文档上传、异步解析、分块、向量化与状态跟踪
- 多通道检索、去重、重排和 TopK 截断
- 查询改写、意图识别、澄清引导和会话记忆
- 多模型路由、健康检查、熔断与自动降级
- MCP 工具发现与调用，支持知识检索和业务工具协同
- SSE 流式回答、限流和基于 Trace 的链路排障

## 架构概览

```text
Browser
  |
Frontend (React / Vite)
  |
Backend (Spring Boot)
  |-- PostgreSQL + pgvector
  |-- Redis
  |-- RocketMQ
  |-- RustFS (S3-compatible object storage)
  |-- MCP Server
  `-- AI providers (chat / embedding / rerank)
```

后端采用 Maven 多模块结构：`bootstrap` 负责应用与业务实现，`framework` 提供通用基础设施，`infra-ai` 负责模型能力抽象，`mcp-server` 提供独立的 MCP 服务。

详细设计说明见 [架构文档](docs/ragent-architecture.md) 和 [多通道检索说明](docs/multi-channel-retrieval.md)。

## 技术栈

- Backend: Java 17, Spring Boot, MyBatis-Plus, Sa-Token
- Frontend: React 18, TypeScript, Vite
- Storage and middleware: PostgreSQL + pgvector, Redis, RocketMQ, RustFS
- AI integration: Chat, Embedding, Rerank model routing and MCP
- Delivery: Docker Compose, GitHub Actions, Tencent Cloud CVM self-hosted Runner

## 本地启动

### 前置条件

- JDK 17
- Node.js 与 npm
- PostgreSQL + pgvector、Redis、RocketMQ、RustFS 等依赖服务
- 可用的模型供应商配置；MCP 工具能力还需要启动 MCP Server

项目的完整本地环境恢复、依赖服务检查和故障排查说明见 [本地启动指南](docs/project-startup-guide.md)。以下是服务准备完成后的最小启动方式。

### 启动后端

在仓库根目录执行：

```bash
./mvnw spring-boot:run -pl bootstrap
```

Windows PowerShell 可执行：

```powershell
.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

后端默认地址：`http://localhost:9090/api/ragent`

### 启动前端

另开一个终端执行：

```bash
cd frontend
npm ci
npm run dev
```

前端默认地址：`http://localhost:5173`。开发服务器已配置 `/api/ragent` 到后端的代理。

### 常用验证命令

```bash
# 后端打包（跳过测试）
./mvnw clean package -DskipTests

# 前端构建
cd frontend && npm run build
```

## 部署到服务器

生产环境通过 Docker Compose 运行前端、后端、MCP Server 及全部中间件。首次部署前，需要从环境变量模板创建生产配置，并填写数据库、对象存储、模型供应商等密钥；不要提交 `deploy/.env`。

```bash
cd /opt/ragent
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env

# 仅拉取中间件镜像
docker compose --env-file deploy/.env -f deploy/compose.yaml pull \
  postgres redis rmqnamesrv rmqbroker rustfs

# 顺序构建应用镜像
docker compose --env-file deploy/.env -f deploy/compose.yaml build --pull backend
docker compose --env-file deploy/.env -f deploy/compose.yaml build --pull mcp-server
docker compose --env-file deploy/.env -f deploy/compose.yaml build --pull frontend

# 启动服务
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

完整的服务器配置、安全组、日志与备份说明见 [Docker 生产部署指南](docs/docker-production-deployment.md)。生产环境不要执行 `docker compose down -v`，该命令会删除持久化数据卷。

### 自动部署

当前生产发布流程为：本地推送 `master` 分支后，GitHub-hosted Runner 先将本次提交同步到私有 Gitee 镜像；腾讯云 CVM 上的 self-hosted Runner 在持久工作目录中通过 `git fetch` 增量更新该提交，在服务器本机构建 `backend`、`mcp-server`、`frontend` 镜像，并更新这三个应用容器。

该流程不是 GitHub-hosted Runner 构建镜像后由服务器拉取。完整的部署链路、Runner 配置和排障说明见 [GitHub Actions 自动部署交接说明](docs/github-actions-auto-deployment.md)。

## 项目结构

```text
bootstrap/   Spring Boot 入口与业务实现
framework/   通用基础设施：异常、上下文、缓存、限流、链路追踪等
infra-ai/    Chat、Embedding、Rerank、模型路由与熔断抽象
mcp-server/  独立 MCP Server
frontend/    React 管理端与问答界面
deploy/      Docker Compose、镜像构建与部署脚本
resources/   数据库初始化与格式化等资源
docs/        架构、开发、部署与运维文档
```

## 文档索引

| 文档 | 说明 |
| --- | --- |
| [本地启动指南](docs/project-startup-guide.md) | 本地依赖、中间件恢复、启动与故障排查 |
| [架构文档](docs/ragent-architecture.md) | 模块边界与核心流程 |
| [多通道检索](docs/multi-channel-retrieval.md) | 检索通道与后处理机制 |
| [Docker 生产部署](docs/docker-production-deployment.md) | 腾讯云 CVM 的首次部署、运维与安全配置 |
| [GitHub Actions 自动部署](docs/github-actions-auto-deployment.md) | 自动发布流程、Runner 配置与排障 |
| [企业内部 MCP 工具](docs/enterprise-internal-mcp-tools.md) | MCP 工具接入说明 |

## 贡献

提交变更前建议完成与改动相关的构建和测试。后端代码遵循现有 Maven 模块边界，前端代码遵循 `frontend` 目录中的既有规范；涉及配置或部署流程的修改，请同步更新对应的 `docs` 文档。

## License

本项目采用 [Apache License 2.0](LICENSE)。
