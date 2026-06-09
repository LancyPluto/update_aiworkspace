# CI/CD 说明

主仓库通过 GitHub Actions 实现 **dev 分支持续集成 + 轻量持续交付**。

## 工作流

| 文件 | 触发 | 行为 |
|------|------|------|
| `.github/workflows/dev-delivery.yml` | PR → `dev` | 仅跑 CI（测试 + 前端构建） |
| 同上 | `push` → `dev` | CI 通过后 **轻量部署** 生产 |
| 同上 | `workflow_dispatch`（dev 分支） | 可选 `rsync` / `git` 同步模式 |

## 链路（轻量 CD，默认 rsync）

```
push/PR dev
  → backend: mvn test
  → agent-service / worker: pytest
  → admin-frontend + user-web: npm ci && build
  → (仅 push/dispatch) 检测变更服务 → rsync 增量同步 → 仅重建变更的 Docker 服务
  → http://wlcloudai.com
```

### 三种同步方式对比

| 方式 | 脚本 | 速度 | 适用场景 |
|------|------|------|----------|
| **rsync 增量**（默认 CD） | `ci_remote_deploy_light.sh` | 快，只传变更文件 | 日常 CD，不删整目录 |
| **git pull** | `DEPLOY_SYNC_MODE=git` | 最快，无上传 | 服务器能访问 GitHub 时 |
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
# 轻量部署（与 CI 相同）
export DEPLOY_HOST=... DEPLOY_USER=root DEPLOY_PASSWORD='...'
export DEPLOY_SYNC_MODE=rsync   # 或 git
bash deploy/scripts/ci_remote_deploy_light.sh

# 全量 tar 部署（旧方案）
bash deploy/scripts/ci_build_archive.sh /tmp/ai_tool_market_ci.tar.gz
bash deploy/scripts/ci_remote_deploy.sh /tmp/ai_tool_market_ci.tar.gz
```

## 变更服务检测

`deploy/scripts/detect_deploy_services.sh` 根据 `git diff HEAD~1` 映射：

| 变更路径 | 重建服务 |
|----------|----------|
| `backend/**` | backend |
| `worker/**` | worker |
| `agent-service/**` | agent-service |
| `admin-frontend/**` | admin-frontend |
| `user-web/**` | user-web |
| `deploy/docker-compose*` / `deploy/nginx/**` | nginx（及必要时全部） |

## 安全说明

- 密码只存在于 GitHub Secrets 与服务器，不出现在 workflow 日志中。
- 部署 **保留** 服务器 `/root/ai_tool_market/.env`。
- 建议限制 SSH 来源 IP；长期可改为 SSH 密钥。

## 可交付程度

| 环节 | 状态 |
|------|------|
| CI 测试门禁 | ✅ |
| rsync 增量同步 | ✅ 默认 CD |
| git pull 同步 | ✅ 可选 `DEPLOY_SYNC_MODE=git` |
| 按变更选择性重建容器 | ✅ |
| 本地快速同步脚本 | ✅ `quick_sync_local.sh` |
| 生产 .env 保护 | ✅ |
| 全量 tar 兜底 | ✅ `ci_remote_deploy.sh` |
| 自动跑 SQL 种子 | ❌ MySQL 数据在卷中保留 |
| 零停机 / 蓝绿 | ❌ |
