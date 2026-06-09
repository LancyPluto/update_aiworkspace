# AI Tool Market

AI Tool Market 是一个面向中文用户的 AI 工具市场与管理后台项目。当前主线能力包括用户工具市场、任务队列、算力账户、后台工具配置、模型配置、计费日志，以及面向文生图等多模态工具的初步扩展。

## 当前架构

| 模块 | 技术栈 | 职责 |
| --- | --- | --- |
| `backend` | Spring Boot 3 / Java 17 | 用户、工具、任务、算力、计费、模型配置、内部回调接口 |
| `worker` | Python | Redis 任务消费、模型调用、图片落盘、任务结果回写 |
| `user-web` | Vue 3 / Vite | 用户端工具列表、动态表单、任务结果展示 |
| `admin-frontend` | Next.js / React | 管理后台、工具配置、模型配置、计费日志、用户管理 |
| `agent-service` | FastAPI | Agent 编排、受控工具调用、工作区记忆和上下文运行时 |
| `deploy` | Docker Compose | MySQL、Redis、后端、前端、worker 本地编排 |

## 快速启动

推荐开发环境直接使用本机进程启动业务服务，只用 Docker 跑 MySQL 和 Redis：

```powershell
.\start-dev.bat
```

首次启动会自动创建仓库根目录 `.venv`（合并 agent-service + worker 依赖）并执行 `npm install`；若要强制重装依赖：

```powershell
.\scripts\start-dev.ps1 -InstallDeps
```

仅启动基础设施：

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql redis
```

完整 Docker Compose：

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

## 本地地址

| 服务 | 地址 |
| --- | --- |
| 用户端 | http://localhost:5173 |
| 管理后台 | http://localhost:5174 |
| 后端 API | http://localhost:8080 |
| 后端健康检查 | http://localhost:8080/api/health |
| Agent Service | http://localhost:8090 |
| MySQL | 127.0.0.1:3307 |
| Redis | 127.0.0.1:6379 |

## 测试账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `123456` |
| 普通用户 | `user1` | `123456` |
| 禁用用户 | `disabled_user` | `123456` |

## 常用验证

后端：

```powershell
cd backend
mvn test
```

管理后台：

```powershell
cd admin-frontend
npm run build
```

用户端：

```powershell
cd user-web
npm run build
```

Worker 生图假任务：

```powershell
python worker\scripts\run_fake_image_generation_test.py
```

OpenAPI YAML 解析：

```powershell
python -c "import yaml, pathlib; yaml.safe_load(pathlib.Path('docs/api/openapi.yml').read_text(encoding='utf-8')); print('OPENAPI_YAML_OK')"
```

## 模型与计费

后台可配置模型供应商、模型名、Base URL、API Key、控制台链接、余额链接和文档链接。

计费支持两类口径：

| 计费单位 | 适用场景 |
| --- | --- |
| `TOKEN_PER_M` | 文本模型，按百万 token 成本计 |
| `PER_CALL` | 图片、视频、数字人等按生成次数或条数计 |

旧的 `*_per_1k` 价格字段保留用于兼容，启动时会自动迁移到 `*_per_1m`。

## 文档入口

| 文档 | 说明 |
| --- | --- |
| [docs/工作区整理与上传-2026-06-06.md](docs/工作区整理与上传-2026-06-06.md) | 最新分支变更：Suno 音乐生成、字段 UI 分层、供应商元数据 |
| [docs/工作区整理与上传-2026-06-05.md](docs/工作区整理与上传-2026-06-05.md) | oFox 参考图 multipart 修复、工作台重试 |
| [docs/Agent优化与工作区整理-2026-06-04.md](docs/Agent优化与工作区整理-2026-06-04.md) | Agent 记忆、User Web、后台参数整理 |
| [docs/优化清单-2026-06-02.md](docs/优化清单-2026-06-02.md) | 分阶段优化勾选清单 |
| [docs/统一API管理-开发文档.md](docs/统一API管理-开发文档.md) | 统一 API 与协议映射 |
| [docs/archive/2026-06-cleanup/文档上传分支变更记录.md](docs/archive/2026-06-cleanup/文档上传分支变更记录.md) | 历史分支变更记录 |
| [docs/api/openapi.yml](docs/api/openapi.yml) | 接口契约 |

## 开发原则

- 稳定主链路优先：工具配置、任务队列、worker 执行、结果展示、计费日志要先闭环。
- Agent 控制边界：模型只做理解、路由和编排，工具执行、额度、权限、任务状态仍以后端为事实源。
- 多模态工具按类型配置：文案、文生图、视频、数字人应有不同字段模板和结果渲染。
- 数据库变更必须同步 `DataInitializer`、`sql/*.sql` 和测试 schema。
