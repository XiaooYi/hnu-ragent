# GitHub Actions 自动部署（腾讯云生产服务器）

## 1. 部署链路

日常开发只在 Windows 本地项目目录进行：

```text
D:\code\IdeaProjects\ragent
  → git commit
  → git push origin master
  → GitHub Actions
  → 腾讯云 CVM 上的 self-hosted runner
  → 构建镜像并更新应用容器
```

生产服务器不会对旧的 `/home/ubuntu/ragent` 目录执行 `git pull` 或 `git reset --hard`。Runner 会把每次 push 的准确提交检出到自己的工作目录，以避免覆盖服务器上的历史上传文件或私密配置。

工作流定义在 `.github/workflows/deploy-production.yml`，仅在 `master` 分支 push 或手工触发时运行。

## 2. 保持在服务器上的私密配置

GitHub 仓库不得提交 `deploy/.env`。在服务器上创建一个仅 `ubuntu` 可读的持久目录：

```bash
sudo install -d -m 700 -o ubuntu -g ubuntu /home/ubuntu/ragent-secrets
sudo install -m 600 -o ubuntu -g ubuntu \
  /home/ubuntu/ragent/deploy/.env \
  /home/ubuntu/ragent-secrets/ragent.env
```

工作流通过 `RAGENT_DEPLOY_ENV_FILE=/home/ubuntu/ragent-secrets/ragent.env` 使用这个文件。它包含数据库密码、Redis 密码、RustFS 密钥和第三方 AI API Key，绝不能输出到 GitHub Actions 日志。

## 3. 安装 GitHub self-hosted runner

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

## 4. 自动部署执行内容

每次 push 到 `master` 时，Runner 会在独立工作目录中依次执行：

```bash
docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml build --pull backend

docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml build --pull mcp-server

docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml build --pull frontend

docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml up -d --no-deps --force-recreate \
  backend mcp-server frontend
```

顺序构建避免 backend 与 mcp-server 同时 Maven 编译，适合 2 核 8 GiB CVM。工作流只更新 `frontend`、`backend`、`mcp-server`；不会重启或删除 PostgreSQL、Redis、RocketMQ、RustFS，也不会删除 Docker 数据卷。

如需手动更新中间件镜像，单独执行：

```bash
docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f /home/ubuntu/ragent/deploy/compose.yaml pull \
  postgres redis rmqnamesrv rmqbroker rustfs
```

不要在日常部署中执行 `docker compose down -v`。

## 5. 安全约束

- GitHub 仓库应保持私有，或至少保证只有可信协作者能修改 `master` 和 Actions 工作流。
- 工作流只由 `push` 到 `master` 触发；不要添加不受信任 PR 触发器。
- `master` 分支启用保护规则，禁止未经审核的直接推送。
- 不要将 API Key、数据库密码、SSH 私钥、GitHub Runner 注册 token 提交到仓库。
- GitHub Actions 页面中的部署日志可用于确认构建、容器重建和失败原因。
