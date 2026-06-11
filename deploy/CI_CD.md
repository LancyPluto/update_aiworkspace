# CI/CD 说明

主仓库通过 GitHub Actions 实现 **dev 分支持续集成 + 轻量持续交付**。

## 工作流

| 文件 | 触发 | 行为 |
|------|------|------|
| `.github/workflows/dev-delivery.yml` | PR → `dev` | 仅跑 CI（测试 + 前端构建），**不部署生产** |
| 同上 | `push` → `dev`（含 PR merge 后的 push） | CI 通过后 **git 同步 + Docker 重建** |
| 同上 | `workflow_dispatch` | 手动触发部署，可选 `git` / `rsync` |

## 链路（轻量 CD，默认 git）

```
push dev / PR merge → dev
  → backend: mvn test
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

之后 GitHub Actions 使用 `GITHUB_TOKEN` 通过 HTTPS 拉取私有仓库，无需在服务器长期保存 PAT。

### 三种同步方式对比

| 方式 | 脚本 | 速度 | 适用场景 |
|------|------|------|----------|
| **git pull**（默认 CD） | `DEPLOY_SYNC_MODE=git` | 最快，增量 fetch | 日常 CD，生产机已 `bootstrap_production_git.sh` |
| **rsync 增量** | `DEPLOY_SYNC_MODE=rsync` | 只传变更文件 | 服务器无法访问 GitHub 时的兜底 |
| **全量 tar**（旧） | `ci_remote_deploy.sh` | 慢，删整目录 | 兜底 / 首次初始化 |

## 必需 GitHub Secrets

在 **Settings → Environments → `production`**（或 Repository secrets）中配置：

| Secret | 示例值 | 说明 |
|--------|--------|------|
| `DEPLOY_HOST` | `8.134.93.203` | 生产服务器 IP |
| `DEPLOY_USER` | `root` | SSH 用户 |
| `DEPLOY_PASSWORD` | *(仅保存在 GitHub)* | SSH 密码 |

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

- 密码只存在于 GitHub Secrets 与服务器，不出现在 workflow 日志中。
- 部署 **保留** 服务器 `/root/ai_tool_market/.env`。
- 建议限制 SSH 来源 IP；长期可改为 SSH 密钥。

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
