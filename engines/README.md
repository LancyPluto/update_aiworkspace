# PPT 引擎（banana-slides）

本目录存放 PPT 生成引擎源码，与超市 `backend` BFF 通过 HTTP 对接。

## 目录

```text
engines/
  banana-slides/     # 仅保留引擎后端（Flask API）；不含 banana 自带前端
    backend/         # /api/projects、/api/settings 等
  README.md
```

**用户界面**在超市 `user-web` 的 PPT 工作台（见前端开发文档），**不要**启动 `engines/banana-slides` 里上游自带的 `frontend` 服务。

## 与超市的关系

| 组件 | 职责 |
| --- | --- |
| `backend` `/api/v1/ppt/*` | 鉴权、算力、项目 binding、代理引擎 |
| `engines/banana-slides` | 大纲 / 描述 / 出图 / 导出 |
| 管理端 `agent_model_configs` | 模型与 API Key（方案 A 同步到引擎 `/api/settings`） |

用户**不**直接访问本引擎；仅内网或本机 `PPT_ENGINE_BASE_URL`。

## 本地开发

### 1. 引擎环境变量

```bash
cd engines/banana-slides
cp .env.example .env
# 编辑 .env 填入 API Key（冷启动兜底；运营配置以管理端同步为准）
```

### 2. Docker 启动引擎（推荐）

在仓库根目录：

```bash
docker compose -f deploy/docker-compose.yml up -d banana-slides
```

宿主机访问：`http://127.0.0.1:5001`（映射容器 5000）。

### 3. 启动超市 backend

```bash
cd backend
PPT_ENGINE_BASE_URL=http://127.0.0.1:5001 AGENT_ENABLED=false mvn spring-boot:run
```

Docker 内 backend 使用 `PPT_ENGINE_BASE_URL=http://banana-slides:5000`（见 `deploy/docker-compose.yml`）。

## 更新引擎代码

从上游同步（示例，源仓库路径按你本机调整）：

```bash
rsync -a --delete \
  --exclude='.git' --exclude='.venv' --exclude='node_modules' --exclude='frontend' \
  --exclude='__pycache__' --exclude='uploads' --exclude='backend/instance' --exclude='.env' \
  /path/to/banana_slider/banana-slides/ \
  engines/banana-slides/
# 同步后若误带入 frontend，可删除：rm -rf engines/banana-slides/frontend
```

## 相关文档

- `docs/PPT生成工具接入-后端开发文档.md`
- `docs/PPT生成工具接入-前端开发文档.md`
