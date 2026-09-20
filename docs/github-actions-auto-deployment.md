# GitHub Actions 自动部署（腾讯云生产服务器）

## 1. 部署链路

日常开发只在 Windows 本地项目目录进行：

```text
D:\code\IdeaProjects\ragent
  → git commit
  → git push origin master
  → GitHub-hosted Runner 同步到私有 Gitee 镜像
  → 腾讯云 CVM 上的 self-hosted runner
  → git fetch 增量更新 /home/ubuntu/ragent-deploy/source
  → 构建镜像并更新应用容器
```

生产服务器只操作由部署 Runner 专用的 `/home/ubuntu/ragent-deploy/source`，不会操作旧的 `/home/ubuntu/ragent` 目录或私密配置目录。该目录保留 `.git` 元数据，因此后续发布只会从国内 Gitee 镜像传输新增 Git 对象，而不是从 GitHub 下载完整源码压缩包。

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

## 3. 配置私有 Gitee 部署镜像

在 Gitee 创建一个**空的私有仓库**，不要初始化 README、`.gitignore` 或许可证。该仓库只用于生产部署同步，工作流只会强制更新其 `master` 分支。

需要两组彼此独立的 SSH 密钥：

- GitHub-hosted Runner 使用可写密钥，将当前提交推送到 Gitee。
- CVM 上的 `ubuntu` 用户使用只读密钥，从 Gitee 增量拉取提交。

为 GitHub-hosted Runner 生成第一组密钥，并将公钥添加为该 Gitee 仓库具有写权限的 Deploy Key：

```bash
ssh-keygen -t ed25519 -C "ragent-github-mirror" -f ./ragent-gitee-mirror
cat ./ragent-gitee-mirror.pub
```

将 `ragent-gitee-mirror` 的私钥完整内容保存为 GitHub Repository Secret `GITEE_MIRROR_SSH_PRIVATE_KEY`。再创建 Repository Secret `GITEE_MIRROR_SSH_URL`，值为镜像仓库 SSH 地址，例如：

```text
git@gitee.com:your-gitee-account/ragent-deployment.git
```

在 CVM 上生成第二组密钥，将其公钥添加为同一仓库的只读 Deploy Key，并配置 `ubuntu` 用户使用该密钥：

```bash
sudo -u ubuntu mkdir -p /home/ubuntu/.ssh
sudo -u ubuntu ssh-keygen -t ed25519 -C "ragent-cvm-readonly" \
  -f /home/ubuntu/.ssh/ragent-gitee-readonly
sudo -u ubuntu chmod 700 /home/ubuntu/.ssh
sudo -u ubuntu chmod 600 /home/ubuntu/.ssh/ragent-gitee-readonly
sudo -u ubuntu cat /home/ubuntu/.ssh/ragent-gitee-readonly.pub
```

将上面输出的公钥添加为同一仓库的只读 Deploy Key。在 `/home/ubuntu/.ssh/config` 添加以下配置；如果该文件已有其他主机配置，只追加此块：

```sshconfig
Host gitee.com
  HostName gitee.com
  User git
  IdentityFile /home/ubuntu/.ssh/ragent-gitee-readonly
  IdentitiesOnly yes
```

预置 Gitee 的 SSH 主机指纹，随后以 `ubuntu` 验证只读访问，并创建部署专用父目录：

```bash
sudo -u ubuntu sh -c 'ssh-keyscan -H gitee.com >> /home/ubuntu/.ssh/known_hosts'
sudo -u ubuntu chmod 600 /home/ubuntu/.ssh/known_hosts
sudo -u ubuntu git ls-remote \
  git@gitee.com:your-gitee-account/ragent-deployment.git HEAD
sudo install -d -m 755 -o ubuntu -g ubuntu /home/ubuntu/ragent-deploy
```

不要复用 GitHub 镜像推送私钥到 CVM，也不要将任何私钥或 Gitee 地址中的令牌写入仓库。

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

## 5. 自动部署执行内容

每次 push 到 `master` 时，GitHub-hosted Runner 先将准确的 `GITHUB_SHA` 推送到 Gitee 私有镜像。CVM Runner 随后在 `/home/ubuntu/ragent-deploy/source` 执行等价的增量同步：

```bash
git fetch --prune origin +refs/heads/master:refs/remotes/origin/master
git checkout --detach --force "$GITHUB_SHA"
```

完成源码同步后，CVM 仍在本机构建并部署：

```bash
docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml build backend

docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml build mcp-server

docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml build frontend

docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f deploy/compose.yaml up -d --no-deps --force-recreate \
  backend mcp-server frontend
```

顺序构建避免 backend 与 mcp-server 同时 Maven 编译，适合 2 核 8 GiB CVM。工作流只更新 `frontend`、`backend`、`mcp-server`；不会重启或删除 PostgreSQL、Redis、RocketMQ、RustFS，也不会删除 Docker 数据卷。

如需手动更新中间件镜像，单独执行：

```bash
docker compose --env-file /home/ubuntu/ragent-secrets/ragent.env \
  -f /home/ubuntu/ragent-deploy/source/deploy/compose.yaml pull \
  postgres redis rmqnamesrv rmqbroker rustfs
```

不要在日常部署中执行 `docker compose down -v`。

## 6. 安全约束

- GitHub 仓库应保持私有，或至少保证只有可信协作者能修改 `master` 和 Actions 工作流。
- 工作流只由 `push` 到 `master` 触发；不要添加不受信任 PR 触发器。
- `master` 分支启用保护规则，禁止未经审核的直接推送。
- 不要将 API Key、数据库密码、SSH 私钥、GitHub Runner 注册 token 提交到仓库。
- GitHub Actions 页面中的部署日志可用于确认构建、容器重建和失败原因。
