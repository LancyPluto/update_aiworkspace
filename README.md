# AI Tool Market

AI Tool Market 是一个面向中文用户的 AI 工具市场与 Agent 工作台系统。项目提供用户端 AI 工具使用、任务队列执行、模型供应商配置、额度计费、社区内容、PPT 工作流和管理后台能力，并通过独立的 Agent Service 编排复杂工具调用。

## 技术栈

| 层级 | 技术/依赖 | 说明 |
| --- | --- | --- |
| 后端 API | Java 17, Spring Boot 3.3, MyBatis-Plus, Spring JDBC | 用户、工具、任务、计费、Agent 事实源和内部回调接口 |
| 数据库 | MySQL 8.0 | 业务数据、任务状态、模型配置、计费日志、Agent 会话与记忆 |
| 缓存/队列 | Redis 7, RabbitMQ 3.13 | Redis 用于缓存/限流/兼容队列，RabbitMQ 是本地开发默认任务队列 |
| Worker | Python 3.11+, requests, httpx, pika, redis, openai | 消费任务队列，调用文本/图片/音频/视频等模型供应商并回写结果 |
| Agent Service | FastAPI, Uvicorn, Pydantic, Deep Agents, LangChain | Agent 路由、受控工具调用、上下文注入、文件/记忆运行时 |
| 用户端 | Vue 3, Vite, Pinia, Vue Router, Tailwind CSS, lucide-vue-next | 工具市场、Agent 工作台、动态表单、任务结果与社区展示 |
| 管理后台 | Next.js 16, React 19, TypeScript, Radix UI, Recharts, Tailwind CSS | 工具配置、模型配置、统一 API、队列/任务、计费和系统设置 |
| 部署 | Docker Compose, Nginx | 本地/生产编排，反向代理与静态资源服务 |

## 目录结构

```text
.
|-- backend/                 # Spring Boot 后端，系统事实源与核心 API
|   |-- src/main/java/.../
|   |   |-- auth/            # 登录、JWT、验证码/短信
|   |   |-- user/            # 用户与管理端用户接口
|   |   |-- tool/            # 工具、分类、模板、工作流配置
|   |   |-- task/            # 任务创建、状态机、队列发布、worker 回调
|   |   |-- agent/           # Agent 会话、运行、工具偏好、模型配置、记忆
|   |   |-- credit/          # 额度、充值、微信/支付宝/模拟支付
|   |   |-- market/          # AI 工具市场与聊天消息
|   |   |-- ppt/             # PPT 项目、文件、工作流与引擎集成
|   |   |-- community/       # 社区内容与后台审核管理
|   |   `-- admin/           # 后台仪表盘、计费、系统设置、配置包
|   `-- src/main/resources/  # application.yml 与模型供应商元数据
|-- worker/                  # Python 任务执行器
|   |-- task_queue/          # RabbitMQ/Redis 消费器与路由
|   |-- handlers/            # 文本、图片、音频、视频、数字人处理器
|   |-- client/              # 各模型供应商 HTTP 客户端
|   |-- tools/               # 文案类工具 prompt/parser/schema
|   `-- main.py              # worker 进程入口
|-- agent-service/           # FastAPI Agent 编排服务
|   |-- app/api/             # health/internal runs/files/market API
|   |-- app/core/            # AgentRuntime 主流程
|   |-- app/tools/           # 受控工具注册、后端桥接、记忆工具
|   |-- app/security/        # 内部签名与 prompt guard
|   `-- app/main.py          # FastAPI 应用入口
|-- user-web/                # Vue 用户端
|   |-- src/api/             # 后端 API 封装
|   |-- src/pages/           # 页面级视图
|   |-- src/components/      # 业务组件
|   |-- src/store/           # Pinia 状态
|   `-- src/utils/           # 工具展示、结果渲染、媒体适配
|-- admin-frontend/          # Next.js 管理后台
|   |-- app/                 # App Router 页面与 API 代理
|   |-- components/          # 管理端 UI 与业务组件
|   |-- lib/                 # API 客户端、类型、工具函数
|   `-- scripts/             # 管理端辅助脚本
|-- engines/banana-slides/   # PPT 生成引擎子项目
|-- sql/                     # 初始化 SQL 与增量变更脚本
|-- deploy/                  # Docker Compose、Nginx、部署/诊断脚本
|-- docs/                    # 架构、接口、阶段记录与专题文档
|-- data/                    # 本地生成媒体与运行数据
|-- .env.example             # 环境变量模板
`-- start-dev.bat            # Windows 本地开发启动入口
```

## 快速上手

### 环境依赖

- JDK 17+
- Maven 3.8+
- Node.js 22+ 与 npm
- Python 3.11+
- Docker Desktop 或可用的 MySQL/RabbitMQ/Redis 实例

### 配置步骤

1. 复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

2. 按需修改 `.env` 中的模型供应商 Key、支付配置、文件存储、代理和回调地址。

3. 本地开发默认使用：

| 依赖 | 默认地址/账号 |
| --- | --- |
| MySQL | `127.0.0.1:3307`, database `ai_supermarket_v1`, `root/root123456` |
| RabbitMQ | `127.0.0.1:5672`, `guest/guest` |
| Redis | `127.0.0.1:6379` |

4. **（推荐）启用 Git Hook**：在仓库根目录执行 `npm install`，Husky 会在 `git pull` / `git merge`（产生 merge commit）后自动执行未跑过的 `sql/*.sql` 迁移；若有新 SQL 则重启 `backend/agent-service/worker/admin-frontend/user-web/nginx`。跳过：`AI_TOOL_MARKET_SKIP_POST_MERGE=1 git pull`。手动：`npm run post-merge:dev-sync`。

### 启动命令

推荐使用一键开发脚本。脚本会检查基础依赖、按需启动 MySQL/RabbitMQ/Redis、检查 SQL 变更、安装前后端依赖，并分别打开后端、Agent、Worker、用户端和管理端窗口。

```powershell
.\start-dev.bat
```

强制重装依赖：

```powershell
.\scripts\start-dev.ps1 -InstallDeps
```

仅启动基础设施：

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql rabbitmq redis
```

完整 Docker Compose：

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

### 本地地址

| 服务 | 地址 |
| --- | --- |
| 用户端 | http://localhost:5173 |
| 管理后台 | http://localhost:5174 |
| 后端 API | http://localhost:8080 |
| 后端健康检查 | http://localhost:8080/api/health |
| Agent Service | http://localhost:8090 |
| RabbitMQ 管理台 | http://localhost:15672 |

### 测试与验证

```powershell
cd backend
mvn test
```

```powershell
cd agent-service
pytest -q
```

```powershell
cd worker
pytest -q
```

```powershell
cd user-web
npm run build
```

```powershell
cd admin-frontend
npm run typecheck
npm run build
```

OpenAPI YAML 解析：

```powershell
python -c "import yaml, pathlib; yaml.safe_load(pathlib.Path('docs/api/openapi.yml').read_text(encoding='utf-8')); print('OPENAPI_YAML_OK')"
```

## 默认测试账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `123456` |
| 普通用户 | `user1` | `123456` |
| 禁用用户 | `disabled_user` | `123456` |

## 文档入口

| 文档 | 说明 |
| --- | --- |
| [docs/项目整体架构说明.md](docs/项目整体架构说明.md) | 总分结构的架构、模块详情、调用链路与优化建议 |
| [docs/agent/README.md](docs/agent/README.md) | Agent 文档入口：架构与维护指南、链路梳理、历史记录 |
| [docs/api/openapi.yml](docs/api/openapi.yml) | API 契约 |
| [docs/配置包导入导出-维护指南.md](docs/配置包导入导出-维护指南.md) | 配置包 JSON 格式、跨环境导入导出、密钥与 prune 运维说明 |
| [docs/统一API管理-开发文档.md](docs/统一API管理-开发文档.md) | 统一 API 与模型供应商协议映射 |
| [docs/比例Auto规则.md](docs/比例Auto规则.md) | 比例控件、`auto` 语义和新模型接入检查清单 |
| [docs/图片编辑批量补全配置.md](docs/图片编辑批量补全配置.md) | 图片编辑批量补全 `topUpEditBatch` 的账号级配置规则 |
| [docs/Agent优化与工作区整理-2026-06-04.md](docs/Agent优化与工作区整理-2026-06-04.md) | Agent 记忆、工具调用和工作区整理记录 |
| [docs/优化清单-2026-06-02.md](docs/优化清单-2026-06-02.md) | 历史优化清单 |
| [docs/工作区整理与上传-2026-06-09.md](docs/工作区整理与上传-2026-06-09.md) | Agent 附件素材、模型账号解耦、上传素材持久化和本轮收口记录 |
| [docs/工作区整理与上传-2026-06-06.md](docs/工作区整理与上传-2026-06-06.md) | 最近阶段变更记录 |

## 开发约定

- Spring Boot 后端是用户、工具、任务、额度、Agent 运行状态和审计日志的事实源。
- Worker 和 Agent Service 通过内部 API 与后端交互，不直接修改业务数据库。
- 数据库变更需要同步 `sql/*.sql`、后端初始化/测试 schema 和相关文档。
- 新增工具时优先补齐后台配置、字段 schema、worker handler、结果渲染和计费口径。
- 生产敏感配置只放 `.env` 或部署密钥系统，避免提交真实证书和 API Key。
