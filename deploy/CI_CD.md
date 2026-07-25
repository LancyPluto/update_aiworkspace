# CI/CD 说明

PPT 工作台的双仓发布顺序、模型/代理/乱码排查、生产冒烟和一键 PR 入口见 [PPT 工作台部署与排障](../docs/PPT工作台部署与排障.md)。

主仓库通过 GitHub Actions 编排、由当前 Windows 主机内两个隔离的 WSL2 环境中的四个 self-hosted Runner 实例执行 **dev 分支持续集成 + 轻量持续交付**：

- CI WSL 中运行 3 个带 `ci-isolated` 标签的 Runner 实例，仅运行 CI，无生产凭据，并阻断生产地址与本地 LAN。
- CD WSL 中仍只运行 1 个带 `production-deploy` 标签的 Runner 实例，仅运行生产 CD，生产连接凭据只保存在该 WSL 的本地文件中。

两个 WSL 发行版共享同一个网络命名空间，因此 CI 与 CD Runner 必须使用不同的宿主 UID：CI 为 `1000`，CD 为 `1100`。CI 出口规则使用 `iptables -m owner --uid-owner 1000`，禁止改回不带 UID 的全局规则，否则会同时阻断 CD。

### Runner 拓扑与资源隔离

| 实例 | 标签 | 安装目录 | `_work` 目录 | 职责 |
|------|------|------------------|-----------------------|------|
| `local-ci-isolated` | `ci-isolated` | `/opt/actions-runner` | `/opt/actions-runner/_work` | CI |
| `local-ci-isolated-2` | `ci-isolated` | `/opt/actions-runner-2` | `/opt/actions-runner-2/_work` | CI |
| `local-ci-isolated-3` | `ci-isolated` | `/opt/actions-runner-3` | `/opt/actions-runner-3/_work` | CI |
| `local-production-deploy` | `production-deploy` | `/opt/actions-runner`（独立 WSL） | `/opt/actions-runner/_work`（独立 WSL） | 生产 CD |

3 个 CI Runner 使用唯一名称、各自独立的安装目录和 `_work` 目录，并归入 `ci-runners.slice`。每个 Runner service 限制为 `CPUQuota=250%`、`MemoryMax=6G`；slice 聚合限制为 `CPUQuota=600%`、`MemoryHigh=8G`、`MemoryMax=10G`，避免并发构建挤占 CD 与宿主系统资源。后端命令固定使用 `mvn -T 2`，不再按 8 核自动扩展 Maven reactor 线程。

3 个 CI 实例使用同一个无生产权限的 CI 用户，因此共享持久化的本机 Maven、pip 和 npm 缓存：`~/.m2/repository`、`~/.cache/pip`、`~/.npm`。workflow 保留运行时版本设置，但不再启用 setup actions 的 GitHub 远端缓存恢复与上传。各实例的 `_work` 目录不能共享；本机包缓存目录必须由 CI 用户拥有，并纳入定期容量清理。

`actions/setup-python` 的 tool cache 位于各实例独立的 `_work/_tool` 下，因此不能依赖另一个 Runner 已下载的解释器。当前 3 个实例均已预热 Python 3.11 和 3.12 的完整目录及 `x64.complete` 标记；升级 Python patch 版本时必须在 Runner 空闲后同步预热每个实例，避免 job 现场从 GitHub 下载工具链。

`production-deploy` 继续使用独立用户、独立 Runner 凭据、独立工作目录和现有单实例配置，不加入 `ci-isolated` 标签，也不参与 CI 调度。3 个 CI 实例不得添加 `production-deploy` 标签或读取 CD 凭据目录。

### GitHub 网络路由

Windows Mihomo 为 GitHub 相关域名配置 `GitHub-Route` fallback 组，使用现有自动选择代理组作为主路由、`DIRECT` 作为备用。健康检查直接访问 GitHub，配置为 `interval=30`、`lazy=false`、`timeout=7000`、`max-failed-times=1`，以便主路由出现 GitHub TLS 异常时及时切换。Dante 节点不参与当前 CI/CD 路由。

## 工作流

| 文件 | 触发 | 行为 |
|------|------|------|
| `.github/workflows/dev-delivery.yml` | 同仓库 PR → `dev` 的 `synchronize` / `ready_for_review` | 仅跑 CI（测试 + 前端构建），**不部署生产**；`opened` / `reopened` 不触发，fork PR 在受支持事件上明确拒绝 |
| 同上 | `push` → `dev`（含 PR merge 后的 push） | CI 通过后 **git 同步 + Docker 重建** |
| 同上 | `workflow_dispatch`（仅 `dev` ref） | 手动触发 `git` 模式部署；其他 ref 不进入 CD Runner |

## 链路（轻量 CD，默认 git）

```
push dev / PR merge → dev
  → backend: mvn test -T 2
  → agent-service / worker: pytest
  → admin-frontend + user-web: npm ci && build
  → 检测变更服务 → 生产机 git fetch/checkout → 仅重建变更的 Docker 服务
  → http://wlcloudai.com
```

生产机首次需执行一次引导（将现有目录变为 git 仓库）：

```bash
export DEPLOY_HOST=8.134.93.203 DEPLOY_USER=root DEPLOY_PASSWORD='...'
bash deploy/scripts/bootstrap_production_git.sh
```

之后 GitHub Actions 使用单次任务的 `GITHUB_TOKEN` 和临时 `GIT_ASKPASS` 通过 HTTPS 拉取私有仓库；生产 `.git/config` 始终保留无凭据 URL，无需在服务器长期保存 PAT。

### 三种同步方式对比

| 方式 | 脚本 | 速度 | 适用场景 |
|------|------|------|----------|
| **git pull**（默认 CD） | `DEPLOY_SYNC_MODE=git` | 最快，增量 fetch | 日常 CD，生产机已 `bootstrap_production_git.sh` |
| **rsync 增量** | `DEPLOY_SYNC_MODE=rsync` | 只传变更文件 | 服务器无法访问 GitHub 时的兜底 |
| **全量 tar**（旧） | `ci_remote_deploy.sh` | 慢，删整目录 | 兜底 / 首次初始化 |

## CD Runner 本地凭据

不使用 GitHub Secrets 保存生产 SSH 账号。在 `production-deploy` WSL 中创建 `/home/runner/.config/ai-tool-market/deploy.env`：

```dotenv
DEPLOY_HOST=8.134.93.203
DEPLOY_USER=root
DEPLOY_PASSWORD=replace-with-production-password
```

文件必须由 `runner` 用户拥有且权限为 `0600`；工作流会在建立 SSH 连接前强制检查。`/home/runner/.ssh/known_hosts` 必须预先固定并核对生产主机密钥。

| 字段 | 示例值 | 说明 |
|--------|--------|------|
| `DEPLOY_HOST` | `8.134.93.203` | 生产服务器 IP |
| `DEPLOY_USER` | `root` | SSH 用户 |
| `DEPLOY_PASSWORD` | *(仅保存在 CD WSL)* | SSH 密码 |

`git` 模式额外使用 Actions 内置 `GITHUB_TOKEN` 拉取私有仓库，无需单独配置。

## 本地快速同步（排查生产问题）

CD 不是唯一同步方式。开发时可在本机直接增量同步并只重启单个服务：

```bash
export DEPLOY_HOST=8.134.93.203
export DEPLOY_USER=root
export DEPLOY_PASSWORD='...'

# 只同步代码，不重启
bash deploy/scripts/quick_sync_local.sh

# 同步 + 只重启 backend（最快验证后端改动）
bash deploy/scripts/quick_sync_local.sh --restart backend

# 同步 + 重启全部应用容器
bash deploy/scripts/quick_sync_local.sh --restart all
```

**不会**覆盖服务器 `.env`（rsync 排除了 `.env`）。

## 本地脚本（与 CI 同源）

```bash
# 轻量部署（与 CI 相同，Linux/macOS）
export DEPLOY_HOST=... DEPLOY_USER=root DEPLOY_PASSWORD='...'
export DEPLOY_SYNC_MODE=git   # 或 rsync
bash deploy/scripts/ci_remote_deploy_light.sh

# Windows（无 sshpass 时，bundle 同步 + Docker 重建）
set DEPLOY_HOST=8.134.93.203 DEPLOY_USER=root DEPLOY_PASSWORD=...
python deploy/scripts/remote_deploy_production.py

# 全量 tar 部署（旧方案）
bash deploy/scripts/ci_build_archive.sh /tmp/ai_tool_market_ci.tar.gz
bash deploy/scripts/ci_remote_deploy.sh /tmp/ai_tool_market_ci.tar.gz
```

## 本地 vs 生产：为什么页面可能「看起来不一样」

CI 绿灯 **不等于** 浏览器立刻看到与本地 Vite 完全一致的效果，常见原因：

| 原因 | 本地 | 生产 | 相关文件 |
|------|------|------|----------|
| **运行模式不同** | `APP_PRODUCTION_MODE=false` → Vite 开发服（:5173，热更新） | `APP_PRODUCTION_MODE=true` → `npm run build` 静态包，由 nginx 提供 | `.env`、`deploy/docker-compose.yml` |
| **访问入口不同** | 常直接打开 `http://localhost:5173` | 经 `http://wlcloudai.com`（nginx:80） | `deploy/nginx/snippets/app_locations.conf` |
| **增量部署** | — | 仅 **变更路径对应的服务** 会重建；只改 backend 时 user-web 不会重编 | `detect_deploy_services.sh`、`ci_remote_deploy_light.sh` |
| **浏览器缓存** | 开发模式几乎不缓存 | 旧版 `index.html` 可能仍引用过期 JS hash | nginx `Cache-Control`（已对 `index.html` 设 `no-cache`） |
| **数据不同** | 本机 MySQL 数据 | 生产 MySQL 卷内数据（会话、模型、算力等） | 数据库卷，非代码 |

**如何核对生产是否已跟上某次提交：**

1. 生产机：`cd /root/ai_tool_market && git log -1 --oneline`
2. 浏览器访问：`http://wlcloudai.com/build-info.json`（部署后含 `gitSha`）
3. 与本地 `git rev-parse HEAD` 对比

## 变更服务检测

`deploy/scripts/detect_deploy_services.sh` 根据 `git diff HEAD~1` 映射：

| 变更路径 | 重建服务 |
|----------|----------|
| `backend/**` | backend |
| `worker/**` | worker |
| `agent-service/**` | agent-service |
| `admin-frontend/**` | admin-frontend + nginx |
| `user-web/**` | user-web + nginx |
| `deploy/docker-compose*` / `deploy/nginx/**` | nginx（及必要时全部） |

## 安全说明

- 密码只存在于 CD WSL 的 `0600` 本地 `.env` 与生产服务器，不进入仓库或 workflow 日志。
- `production-deploy` Runner 位于组织级 `ai-tool-market-production-deploy` Runner Group；该组仅允许 `AI-miniLab/ai-tool-market/.github/workflows/dev-delivery.yml@refs/heads/dev`。仅靠自定义标签不是权限边界。
- Windows 的 `.wslconfig` 必须设置 `vmIdleTimeout=-1`，并在 Administrator 登录后启动两个 WSL 保活任务，否则空闲回收会让 Runner 离线。
- SSH 必须使用固定的 `known_hosts` 和 `StrictHostKeyChecking=yes`。
- 部署 **保留** 服务器 `/root/ai_tool_market/.env`。
- 建议限制 SSH 来源 IP；长期可改为 SSH 密钥。
- 商业上线前执行 [商业上线 P0 发布门禁](../docs/商业上线P0发布门禁-2026-06-30.md)：敏感文件不得被 `git ls-files .env WXcert _remote.py _fix_remote.py` 返回，生产必须使用 `APP_PRODUCTION_MODE=true` 或 `APP_ENV=production`，并保持 `TASK_QUEUE_BACKEND=rabbitmq`。
- 生产环境会拒绝默认内部 token、默认模型 key、mock provider 和 Redis 任务队列；如部署失败，优先检查服务器 `.env` 与 CD Runner 本地 `deploy.env`。

## 可交付程度

| 环节 | 状态 |
|------|------|
| CI 测试门禁 | ✅ |
| git 增量同步 | ✅ 默认 CD（`DEPLOY_SYNC_MODE=git`） |
| rsync 增量同步 | ✅ 兜底 `DEPLOY_SYNC_MODE=rsync` |
| 生产 git 首次引导 | ✅ `bootstrap_production_git.sh` / `.py` |
| 按变更选择性重建容器 | ✅ |
| 本地快速同步脚本 | ✅ `quick_sync_local.sh` |
| 生产 .env 保护 | ✅ |
| 全量 tar 兜底 | ✅ `ci_remote_deploy.sh` |
| 自动跑 SQL 种子 | ❌ MySQL 数据在卷中保留 |
| 零停机 / 蓝绿 | ❌ |
