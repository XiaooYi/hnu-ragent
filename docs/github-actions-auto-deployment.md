# GitHub Actions 自动部署（腾讯云生产服务器）

## 1. 部署链路

日常开发只在 Windows 本地项目目录进行：

```text
D:\code\IdeaProjects\ragent
  → git commit
  → git push origin master
  → GitHub-hosted Runner：挑选变更涉及的服务，构建镜像并推送到腾讯云 TCR
  → 腾讯云 CVM 上的 self-hosted runner：从 TCR 拉取不可变镜像，重建对应容器
```

镜像构建完全在 GitHub-hosted Runner 上完成，生产服务器不再执行 Maven / npm 构建，也不需要同步源码。服务器只从 TCR 拉取已经构建好的镜像。

工作流定义在 `.github/workflows/deploy-production.yml`，仅在 `master` 分支 push 或手工触发时运行。

> **历史说明**：早期版本通过私有 Gitee 镜像同步源码、再由 CVM 本机执行 `docker compose build`。该方案已被移除，工作流中不得再引入 Gitee 相关配置——`scripts/production-deployment-contract.test.mjs` 中有断言守护这一点。如果看到任何仍然描述 Gitee 镜像的文档或说明，以本文为准。

## 2. 保持在服务器上的私密配置

GitHub 仓库不得提交 `deploy/.env`。在服务器上创建一个仅 `ubuntu` 可读的持久目录：

```bash
sudo install -d -m 700 -o ubuntu -g ubuntu /home/ubuntu/ragent-secrets
sudo install -m 600 -o ubuntu -g ubuntu \
  /home/ubuntu/ragent/deploy/.env \
  /home/ubuntu/ragent-secrets/ragent.env
```

工作流通过 `RAGENT_DEPLOY_ENV_FILE=/home/ubuntu/ragent-secrets/ragent.env` 使用这个文件。它包含数据库密码、Redis 密码、RustFS 密钥和第三方 AI API Key，绝不能输出到 GitHub Actions 日志。

## 3. 配置腾讯云 TCR

生产镜像存放在腾讯云容器镜像服务（TCR）的 `ccr.ccs.tencentyun.com` 实例下，命名空间为 `hnu-ragent`。三个应用镜像的地址形如：

```text
ccr.ccs.tencentyun.com/hnu-ragent/ragent-backend:<GITHUB_SHA>
ccr.ccs.tencentyun.com/hnu-ragent/ragent-mcp-server:<GITHUB_SHA>
ccr.ccs.tencentyun.com/hnu-ragent/ragent-frontend:<GITHUB_SHA>
```

标签使用提交 SHA，是**不可变**的：同一个标签永远指向同一次构建，回滚时可以直接按标签或摘要重新拉取。

需要在 GitHub 仓库中配置两个 Repository Secret：

| Secret | 用途 |
| --- | --- |
| `TCR_USERNAME` | TCR 登录用户名 |
| `TCR_PASSWORD` | TCR 登录密码或访问凭证 |

这两个凭证同时被 GitHub-hosted Runner（推送镜像）和 CVM runner（拉取镜像）使用。具体的实例开通、命名空间创建和访问凭证生成步骤以腾讯云控制台为准。

## 4. 安装 GitHub self-hosted runner

在 GitHub 仓库 `XiaooYi/hnu-ragent` 中依次打开：

```text
Settings → Actions → Runners → New self-hosted runner → Linux → x64
```

在服务器以 `ubuntu` 用户执行 GitHub 页面生成的下载、解压和 `config.sh` 命令。注册时使用标签：

```text
ragent-prod
```

GitHub 生成的注册 token 是短时有效的，不能写入仓库或文档。完成注册后，将 Runner 配置成系统服务：

```bash
cd /home/ubuntu/actions-runner
sudo ./svc.sh install ubuntu
sudo ./svc.sh start
sudo ./svc.sh status
```

Runner 必须运行在 `ubuntu` 用户下；该用户需要具备 Docker 权限：

```bash
id -nG ubuntu
```

输出中应包含 `docker`。

## 5. 工作流执行内容

工作流分为三个阶段，只有前两个阶段跑在 GitHub-hosted Runner 上。

### 5.1 `detect-changes`：挑选受影响的服务

对本次 push 的改动范围执行 `git diff --name-only`，交给 `scripts/detect-affected-services.mjs` 分类，输出本次需要重建的服务列表和构建矩阵。

判定规则概要：

- `frontend/`、`deploy/frontend.Dockerfile` → `frontend`
- `bootstrap/`、`deploy/backend.Dockerfile` → `backend`
- `mcp-server/`、`deploy/mcp-server.Dockerfile` → `mcp-server`
- `framework/`、`infra-ai/`、根 `pom.xml`、`mvnw`、`.mvn/` → `backend` + `mcp-server`
- `deploy/compose.yaml`、`deploy/deploy-from-actions.sh`、`deploy/rocketmq/`、`resources/database/`、本工作流文件本身 → 三个服务全部重建
- `README.md`、`docs/`、`.agents/`、`.specify/`、`scripts/` → 忽略，不触发构建
- 其他未识别的路径 → 按全量发布处理，避免漏发

手工触发（`workflow_dispatch`）没有可信的改动范围，因此**总是全量发布**。

### 5.2 `build-and-push`：构建并推送镜像

按上一步的矩阵并发构建，每个服务一个 job：

```bash
docker buildx build \
  --file deploy/<service>.Dockerfile \
  --tag ccr.ccs.tencentyun.com/hnu-ragent/ragent-<service>:$GITHUB_SHA \
  --cache-from type=gha,scope=ragent-<service> \
  --cache-to type=gha,mode=max,scope=ragent-<service> \
  --push .
```

job 超时为 90 分钟。构建缓存存放在 GitHub Actions Cache 中，跨次运行复用 Maven 依赖层。

### 5.3 `deploy`：在 CVM 上替换容器

由 CVM 上的 self-hosted runner 执行。这台机器**访问不到 `github.com`，但可以访问 `api.github.com`**，因此部署所需的文件是通过 GitHub Contents API 逐个拉取的，而不是 `git clone`：

```text
deploy/compose.yaml
deploy/deploy-from-actions.sh
deploy/rocketmq/broker.conf
resources/database/schema_pg.sql
resources/database/init_data_pg.sql
```

随后 `deploy/deploy-from-actions.sh` 负责实际的发布：

1. 用 `flock` 加锁，避免并发发布互相干扰。
2. 登录 TCR。
3. 只对本次受影响的服务执行 `docker compose pull` 和 `up -d --no-deps --force-recreate`；未受影响的服务沿用上一次成功的镜像摘要。
4. 等待 10 秒后检查容器是否仍在运行，失败则回滚到 `/home/ubuntu/ragent-deploy/last-successful-release.env` 记录的上一组镜像摘要。
5. 成功后把本次三个服务的镜像摘要写入该文件。

`deploy/compose.yaml` 中的应用服务只声明 `image:`，没有任何 `build:` 段——镜像一律来自 TCR。

工作流只更新 `frontend`、`backend`、`mcp-server`；不会重启或删除 PostgreSQL、Redis、RocketMQ、RustFS，也不会删除 Docker 数据卷。

## 6. 镜像分层与推送耗时

### 6.1 为什么需要分层

GitHub-hosted Runner 与腾讯云 TCR 之间是跨境链路，推送耗时是这条流水线上最大的变量。早期镜像把整个 Spring Boot fat jar 作为**单个** `COPY` 层：

- backend 的 fat jar 约 169 MB，且位于 `.m2` 依赖与应用类混杂的整包中
- 只要改一行业务代码，整个 jar 的字节就变了，在镜像仓库看来是一个全新的层
- 于是**每次提交都要把全部依赖重新上传一遍**

这正是后端构建曾经跑满 30 分钟超时的原因。

### 6.2 分层后的结构

两个 Dockerfile 的构建阶段都增加了 Spring Boot 的 `tools` jarmode 提取：

```dockerfile
java -Djarmode=tools -jar bootstrap/target/bootstrap-*.jar \
    extract --layers --launcher --destination bootstrap/target/extracted
```

运行时阶段把四个层逐一 `COPY` 进 `/app`（顺序固定，依赖层在前、应用层在后）：

```dockerfile
COPY --from=build /workspace/bootstrap/target/extracted/dependencies/ ./
COPY --from=build /workspace/bootstrap/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/bootstrap/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/bootstrap/target/extracted/application/ ./
```

实测各层体积：

| 层 | backend | mcp-server | 内容 |
| --- | --- | --- | --- |
| `dependencies` | 168 MB | 28 MB | 第三方依赖 jar |
| `spring-boot-loader` | 1 MB | 1 MB | Spring Boot 启动器类 |
| `snapshot-dependencies` | 0 | 0 | 恒为空，见下 |
| `application` | 5 MB | 110 KB | 业务类与资源 |

`snapshot-dependencies` 在使用 Spring Boot 默认 `layers.idx` 时**恒为空**（默认索引里 `BOOT-INF/lib/` 先被 `dependencies` 匹配）。保留这一行只是为了与官方四层写法一致，将来若自定义 `layers.idx` 也不会漏文件。

由于依赖层只由依赖集合决定，**只改业务代码时它的内容与摘要完全不变，镜像仓库会跳过上传，实际只传 `application` 层**。

### 6.3 预期耗时

| 场景 | 需要上传的内容 | 预期 |
| --- | --- | --- |
| 首次推送（依赖层尚未进入 TCR） | 全部依赖层 + 基础镜像 | 数分钟；链路差时可能显著更久，靠 90 分钟超时兜住 |
| 只改业务代码 | `application` 层（backend 约 5 MB） | 数秒到十几秒 |
| 改动了依赖（`pom.xml`） | 依赖层重新上传 | 同首次推送 |

跨境链路本身波动很大：实测同一量级的数据传输，好的时段约 1.5 MB/s，差的时段会掉到 200 KB/s 以下（相差近十倍）。分层对链路波动的意义在于把每次要传的字节数压到最小，从而让最坏情况也可控。

### 6.4 如何验证分层是否生效

在 `build-and-push` 的日志里找这一行：

```text
#30 pushing layers 116.4s done
```

- 值在**几秒到十几秒**量级 → 依赖层被仓库跳过，分层生效
- 值在**分钟**量级且每次提交都如此 → 说明仓库没有按 OCI 规范跳过已存在的 blob，需要改用其他手段（依赖瘦身、更小的基础镜像等）

对比参照：`exporting layers`（本地打包）通常只需几秒，两者耗时差距悬殊就说明瓶颈在网络而非本地。

### 6.5 修改镜像时注意

- **不要调整四个 `COPY` 的顺序**，依赖层必须排在应用层之前，否则应用层一变就会连带失效后续所有层。
- **不要在运行时阶段改回 `-jar /app/app.jar`**。分层后镜像里不再有单一 jar，启动方式是 `org.springframework.boot.loader.launch.JarLauncher`。
- 基础镜像 `eclipse-temurin:17-jre-jammy` 目前按 tag 引用。上游重新打 tag 会导致三个基础层重新上传；如需进一步稳定，可以改为按 digest 固定。

## 7. 排障

- **构建超时**：查看 `build-and-push` 日志中 `pushing layers` 的耗时，对照 6.4 判断是链路问题还是分层失效。
- **部署失败**：`deploy-from-actions.sh` 会自动回滚并在日志中打印失败服务的最近 120 行容器日志。
- **服务器拉不到镜像**：确认 `TCR_USERNAME` / `TCR_PASSWORD` 是否过期，以及 CVM 与 TCR 是否处于同一地域（跨地域会走公网，速度与费用都会明显变差）。
- **想跳过某次构建**：只改 `docs/`、`README.md`、`scripts/` 下的文件不会触发任何服务重建。

## 8. 安全约束

- GitHub 仓库应保持私有，或至少保证只有可信协作者能修改 `master` 和 Actions 工作流。
- 工作流只由 `push` 到 `master` 触发；不要添加不受信任 PR 触发器。
- `master` 分支启用保护规则，禁止未经审核的直接推送。
- 不要将 API Key、数据库密码、SSH 私钥、GitHub Runner 注册 token 提交到仓库。
- `TCR_USERNAME` / `TCR_PASSWORD` 只存放在 GitHub Repository Secret 与服务器环境文件中，不要写入仓库。
- GitHub Actions 页面中的部署日志可用于确认构建、容器重建和失败原因。
