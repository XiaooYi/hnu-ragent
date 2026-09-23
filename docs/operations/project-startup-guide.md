# Ragent 本地项目启动与虚拟机中间件恢复指南

> 适用场景：Windows 本机运行 Ragent 后端与前端；中间件运行在局域网虚拟机中。本文针对当前仓库配置及已验证的 Docker 容器名称编写，适合“此前已经成功运行过、间隔较久后恢复启动”的场景。

## 1. 启动拓扑与原则

当前配置中，主后端运行在 Windows 本机，依赖虚拟机 `192.168.227.128` 上的中间件。完整启动顺序如下：

```text
虚拟机网络
  -> PostgreSQL + pgvector
  -> Redis
  -> RustFS
  -> RocketMQ NameServer
  -> RocketMQ Broker
  -> RocketMQ Dashboard（可选）
  -> MCP Server（完整 Agent 工具能力需要）
  -> Windows 本机 Ragent 后端
  -> Windows 本机前端
```

恢复已有环境时，请遵循以下原则：

- **不要**重新创建数据库、重新导入 `schema_pg.sql`，也不要删除 Docker volume；这些操作可能清空已有用户、会话、知识库、文档或向量数据。
- 优先使用 `docker start <原容器名>` 恢复原容器，而不是新建 `docker run` 容器。这样可以保留原有环境变量、网络、挂载目录和数据卷。
- RocketMQ 的顺序必须是 **NameServer -> Broker -> Dashboard**。Dashboard 只是管理界面，不参与消息收发。

## 2. 当前项目的实际依赖

| 服务 | 当前地址/端口 | 是否必须 | 用途 |
| --- | --- | --- | --- |
| PostgreSQL + pgvector | `192.168.227.128:5432` | 是 | 业务数据、会话、知识库元数据、向量数据 |
| Redis | `192.168.227.128:6379` | 是 | 登录态、Redisson 锁、限流、缓存 |
| RustFS（S3 兼容） | `192.168.227.128:9000` | 是 | 文档上传、原文件和图片解析产物 |
| RocketMQ NameServer | `192.168.227.128:9876` | 是 | Broker 路由注册和客户端发现 |
| RocketMQ Broker | 由 NameServer 发现 | 是 | 文档异步处理、反馈等消息的实际收发 |
| MCP Server | `192.168.227.128:9099/mcp` | Agent 工具时需要 | 天气、工单、销售等远程工具 |
| Milvus | `192.168.227.128:19530` | 当前不需要 | 仅 `rag.vector.type=milvus` 时使用 |
| Ragent 后端 | `localhost:9090/api/ragent` | 是 | Spring Boot 主应用 |
| 前端 | `localhost:5173` | 是 | React + Vite 开发服务器 |

当前 [application.yaml](../../bootstrap/src/main/resources/application.yaml) 的 `rag.vector.type` 为 `pg`，因此项目现在使用 **PostgreSQL pgvector** 检索；Milvus 未启动不应阻塞当前主链路。

## 3. 启动前检查

### 3.1 Windows 本机：确认虚拟机可访问

在 Windows PowerShell 中执行：

```powershell
$vmIp = "192.168.227.128"
ping $vmIp

$ports = 5432, 6379, 9876, 9000, 9099
foreach ($port in $ports) {
    $ok = Test-NetConnection -ComputerName $vmIp -Port $port -InformationLevel Quiet
    Write-Host "$vmIp`:$port -> $ok"
}
```

服务未启动前端口显示 `False` 属于正常现象。待虚拟机服务恢复后，`5432`、`6379`、`9876`、`9000` 应为 `True`；完整启用 Agent 工具时，`9099` 也应为 `True`。

### 3.2 登录虚拟机，盘点原服务

```bash
ssh <虚拟机用户名>@192.168.227.128
sudo -i

docker ps -a
systemctl list-unit-files --type=service | grep -Ei 'postgres|redis|rocket|rustfs|milvus|mcp'
jps -l
```

本环境已确认使用 Docker，并观察到以下容器名称：

| 组件 | 容器名 | 说明 |
| --- | --- | --- |
| RocketMQ NameServer | `rmqnamesrv` | 端口 `9876` 对外映射 |
| RocketMQ Broker | `rmqbroker` | 必须在 NameServer 后启动 |
| RocketMQ Dashboard | `rocketmq-dashboard` | 推荐使用的管理界面 |
| 旧版 RocketMQ Console | `rmqconsole` | 与 Dashboard 功能重复，通常不必启动 |
| PostgreSQL | `postgres` | 当前项目使用此库及 pgvector |
| Redis | `redis` | 当前项目的锁、限流和缓存依赖 |
| RustFS | `rustfs` | 当前项目启动时会检查/初始化资源 bucket |

若实际容器名称发生变化，以 `docker ps -a` 输出为准，并将本文命令中的名称替换为真实容器名。

## 4. 在虚拟机恢复中间件

### 4.1 PostgreSQL + pgvector

```bash
docker start postgres
docker ps --filter "name=postgres"
docker logs --tail 100 postgres
```

确认数据库能够接受连接。若容器内有 PostgreSQL 客户端，可检查：

```bash
docker exec -it postgres pg_isready -U postgres -d ragent
```

应出现类似 `accepting connections` 的结果。

不要在已有恢复环境中重新执行建库或建表脚本。只有确认数据盘已丢失、需要从零初始化时，才应使用仓库中的 [schema_pg.sql](../../resources/database/schema_pg.sql)。

### 4.2 Redis

```bash
docker start redis
docker ps --filter "name=redis"
docker logs --tail 100 redis
```

验证 Redis：

```bash
docker exec -it redis redis-cli PING
```

如果 Redis 配置了密码，在交互式 `redis-cli` 中先执行 `AUTH <密码>`，再执行：

```text
PING
```

预期结果为：

```text
PONG
```

### 4.3 RustFS

```bash
docker start rustfs
docker ps --filter "name=rustfs"
docker logs --tail 100 rustfs
```

检查端口：

```bash
ss -lntp | grep -E ':9000|:9001'
```

RustFS 的 `9000` 是 S3 API 端口，`9001` 通常是控制台端口。后端的 `AssetBucketInitializer` 在启动期会连接 RustFS 和 Redis，并确保资源 bucket 存在；因此 RustFS 未恢复时，后端可能直接启动失败。

### 4.4 RocketMQ：NameServer -> Broker -> Dashboard

#### 第一步：NameServer

```bash
docker start rmqnamesrv
docker ps --filter "name=rmqnamesrv"
docker logs --tail 100 rmqnamesrv
```

NameServer 正常后，确认 `9876` 已监听：

```bash
ss -lntp | grep ':9876'
```

如果 `docker ps` 显示 `rmqnamesrv` 为 `Up (healthy)`，则无需重复启动。

#### 第二步：Broker

Broker 必须等待 NameServer 正常后才能启动：

```bash
docker start rmqbroker
docker ps -a --filter "name=rmqbroker"
docker logs --tail 200 rmqbroker
```

正常状态应为 `Up`。如果容器重新变为 `Exited (255)`，不要反复重启；先保留并分析上面的日志。常见原因包括：

- Broker 配置的 NameServer 地址不可达；
- Broker 公布的 IP 地址已经因虚拟机网络变化而失效；
- Broker 数据目录或挂载目录权限异常；
- JVM 内存不足；
- Broker 配置文件、Docker 网络或端口映射发生变化。

`9876` 仅表示 NameServer 可用。Ragent 的生产者/消费者需要 Broker 正常注册，才能完成文档异步分块、入库和反馈消息等链路。

#### 第三步：Dashboard（可选）

`rocketmq-dashboard` 和 `rmqconsole` 都是管理界面，只启动一个即可。优先使用较新的 Dashboard：

```bash
docker start rocketmq-dashboard
docker ps -a --filter "name=rocketmq-dashboard"
docker logs --tail 100 rocketmq-dashboard
docker port rocketmq-dashboard
```

若 `docker port rocketmq-dashboard` 输出例如：

```text
8080/tcp -> 0.0.0.0:8080
```

则可从 Windows 浏览器访问：

```text
http://192.168.227.128:8080
```

若没有任何端口输出，说明该容器在创建时未发布 Web 端口；这不影响 Ragent 使用 RocketMQ，但不能仅通过 `docker start` 让 Dashboard 从 Windows 浏览器访问。

不要同时启动旧版 `rmqconsole`，除非确认旧 Dashboard 已不再使用且需要排查历史配置。

### 4.5 MCP Server（完整 Agent 工具能力需要）

主后端会在启动期尝试连接：

```text
http://192.168.227.128:9099/mcp
```

先在虚拟机确认该端口或原服务是否已经存在：

```bash
ss -lntp | grep ':9099'
```

如果 MCP 服务没有恢复，主后端通常会跳过远程工具注册，普通知识库问答仍可验证；但天气、工单、销售等 Agent 工具不会可用。MCP 服务就绪后，应重启一次主后端，使其重新发现并注册远程工具。

## 5. 虚拟机服务恢复后的连通性验收

回到 Windows PowerShell，再执行：

```powershell
$vmIp = "192.168.227.128"
$ports = 5432, 6379, 9876, 9000, 9099
foreach ($port in $ports) {
    $ok = Test-NetConnection -ComputerName $vmIp -Port $port -InformationLevel Quiet
    Write-Host "$vmIp`:$port -> $ok"
}
```

最低可启动集合是：PostgreSQL、Redis、RustFS、RocketMQ NameServer 和 Broker。MCP 连接成功后，Agent 工具功能才完整。

## 6. 启动 Ragent 后端

在 Windows PowerShell 中执行：

```powershell
cd D:\code\IdeaProjects\ragent
java -version
.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

项目要求 **JDK 17**。后端启动成功后监听：

```text
http://localhost:9090/api/ragent
```

重点查看日志是否出现以下类型的信息：

- `Started RagentApplication`：Spring Boot 主应用启动成功；
- S3/RustFS bucket 已就绪：对象存储连接正常；
- 连接 MCP Server 并发现工具：远程 Agent 工具已注册；
- RocketMQ 消费者正常启动：异步文档处理链路可用。

如果 MCP 在主后端之后才启动，请先在后端终端按 `Ctrl + C` 停止，再重新执行上述 Maven 命令。

## 7. 启动前端

另开一个 Windows PowerShell 窗口：

```powershell
cd D:\code\IdeaProjects\ragent\frontend
npm run dev
```

如果 `node_modules` 已被删除，或 Node.js 版本变化较大，先安装锁定版本依赖：

```powershell
npm ci
npm run dev
```

打开：

```text
http://localhost:5173
```

前端已经配置 Vite 代理：

```text
浏览器 /api/ragent/*
  -> Vite :5173
  -> Ragent 后端 http://localhost:9090/api/ragent/*
```

因此本地开发通常不需要额外处理 CORS 或修改前端 API 地址。

## 8. 最终验收清单

按由浅入深的顺序验证：

1. 浏览器能够打开 `http://localhost:5173`；
2. 能使用已有账号登录；
3. 管理端能加载已有知识库、用户、会话等数据；
4. 上传一个小型 Markdown 或 TXT 文档；
5. 确认 RocketMQ 消费、分块和向量入库日志正常；
6. 在聊天页询问知识库中明确存在的问题，确认收到 SSE 流式回答；
7. 最后验证一个需要 MCP 工具的问题，确认远程工具被调用。

“后端启动成功”不等于“问答一定成功”。当前默认 Chat、Embedding、Rerank 模型包含云端供应商；若首次对话失败，还需要检查模型供应商的网络、凭据、余额/额度和模型名称。本机 `localhost:11434` 的 Ollama 仅是候选模型服务，是否需要启动取决于当前实际使用的模型路由。

## 9. 常见故障定位

| 现象 | 优先检查 |
| --- | --- |
| 后端报 PostgreSQL 连接超时/拒绝 | `postgres` 是否 Up、虚拟机 `5432` 端口、数据库 `ragent` 是否存在 |
| 后端报 Redis/Redisson 错误 | `redis` 是否 Up、`6379` 连通性、现有 Redis 密码是否匹配 |
| 后端启动期 RustFS 或 bucket 初始化失败 | `rustfs` 是否 Up、`9000` 连通性、原账号/密钥和数据卷是否保留 |
| 文档上传后长期不处理 | `rmqnamesrv` 与 `rmqbroker` 是否均 Up，Broker 日志和后端消费者日志 |
| `rmqbroker` 退出码为 `255` | 立即执行 `docker logs --tail 200 rmqbroker`，按日志检查 Broker 配置、网络、目录权限和内存 |
| MCP 工具未出现 | `9099` 是否可达；MCP 在后端启动后恢复时，重启后端以重新注册工具 |
| 页面可打开但问答失败 | 检查云模型网络/凭据/额度，或本地 Ollama 与所需模型是否就绪 |
| Dashboard 启动但 Windows 无法访问 | 执行 `docker port rocketmq-dashboard`，确认创建容器时是否发布 Web 端口 |

## 10. 推荐停止顺序

日常结束开发时，先停本机应用，再停虚拟机服务，避免应用仍在向中间件发请求：

```text
前端 -> Ragent 后端 -> MCP Server -> RocketMQ Dashboard -> Broker -> NameServer -> RustFS -> Redis -> PostgreSQL
```

在虚拟机中对应容器名称未变化时，可依次执行：

```bash
docker stop rocketmq-dashboard
docker stop rmqbroker
docker stop rmqnamesrv
docker stop rustfs
docker stop redis
docker stop postgres
```

停止时不要附加删除 volume、镜像或数据目录的参数。
