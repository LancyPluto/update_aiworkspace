# 云端部署安全与稳定性优先级审查

审查日期：2026-05-14  
审查范围：当前工作区代码与部署配置，包括 `backend`、`admin-frontend`、`user-web`、`worker`、`agent-service`、`deploy/docker-compose.yml`、`sql`。  
目标规模：100-10000 用户的云端服务。  

## 结论摘要

当前项目已经具备基础的 JWT 鉴权、管理员接口隔离、内部接口 HMAC 签名、任务 outbox、算力冻结/结算、Agent 限流等雏形，但仍不建议直接按当前 Docker 配置上线。主要原因是生产部署仍使用开发模式容器、默认账号和弱密钥、数据库与内部服务端口暴露、Cookie 凭证缺少 CSRF 防护、Agent-service 入站签名默认关闭、Redis list 队列存在 worker 崩溃丢任务风险。

建议上线前至少完成 P0 项；如果面向公开互联网用户，P1 也应作为首版生产准入项。

## P0：上线前必须修补

### P0-1 生产 Docker 配置仍是开发形态

证据：`deploy/docker-compose.yml` 中 `backend` 使用 `mvn spring-boot:run`，前端容器执行 `npm install && npm run dev`，并将源码目录挂载进容器；MySQL、Redis、agent-service 也映射到宿主端口。

风险：开发服务器性能与安全基线不足；源码和构建依赖暴露面过大；服务重启慢；依赖安装在运行期发生，容易被网络和供应链问题影响；Redis/MySQL 暴露到公网时可被直接攻击。

修复建议：
- 新增生产 compose 或 Kubernetes manifest，使用多阶段 Dockerfile 构建不可变镜像。
- `backend` 运行 `java -jar`，前端使用静态构建或 Next 生产启动，禁止运行期 `npm install`。
- MySQL、Redis、backend internal、agent-service internal 仅在内网网络暴露，不对公网映射端口。
- 增加资源限制、健康检查、日志采集和滚动重启策略。

验收标准：
- 生产环境 `docker compose config` 中不出现 `npm run dev`、`mvn spring-boot:run`、源码 bind mount。
- 公网只暴露网关/前端入口，MySQL/Redis/agent-service 不可从公网访问。

### P0-2 默认管理员账号和默认用户会自动创建

证据：`backend/src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java` 启动时创建 `admin/123456` 和 `user1/123456`。

风险：如果生产库首次启动未修改逻辑，攻击者可以用默认管理员密码进入后台，接管用户、算力、工具、模型配置和密钥。

修复建议：
- 生产模式禁止自动创建默认账号。
- 首次管理员创建应使用一次性初始化令牌、环境变量注入强密码，或部署后手动执行受控脚本。
- 启动校验：`APP_PRODUCTION_MODE=true` 时若检测到默认账号仍为默认密码，直接启动失败。

验收标准：
- 生产模式下无默认 `admin/123456`。
- 自动化测试覆盖生产模式禁止默认账号创建。

### P0-3 默认密钥和明文配置仍容易被误用

证据：`application.yml` 和 `.env.example` 使用 `local-dev-secret`、`local-internal-token`、`root123456`；`StartupSecurityValidator` 只在 `APP_PRODUCTION_MODE=true` 时拦截部分默认值。

风险：环境变量漏配或 `APP_PRODUCTION_MODE=false` 被用于云端时，JWT、内部接口签名、数据库密码都处于可猜测状态。

修复建议：
- 生产部署强制 `APP_PRODUCTION_MODE=true`，并在 CI/CD 中校验。
- 移除生产 compose 中所有默认 secret fallback。
- 使用云厂商 Secret Manager/KMS 管理 `JWT_SECRET`、`INTERNAL_API_TOKEN`、数据库密码、模型 API Key。
- 数据库和模型 API Key 建议加密存储，至少不要明文落库。

验收标准：
- 生产启动缺少强 secret 时失败。
- 密钥不出现在 git、镜像层、容器日志和普通 API 响应中。

### P0-4 Agent-service 内部入站签名默认关闭

证据：`deploy/docker-compose.yml` 和 `.env.example` 中 `AGENT_VERIFY_INTERNAL_SIGNATURE=false`；`agent-service/app/api/internal_runs.py` 仅当该开关开启时验证 HMAC。

风险：如果 agent-service 端口暴露或被内网横向访问，攻击者可直接触发 Agent run、模型配置测试和文件解析，造成算力消耗、数据泄漏或服务阻塞。

修复建议：
- 生产默认改为 `AGENT_VERIFY_INTERNAL_SIGNATURE=true`。
- agent-service 端口只允许 backend 所在安全组访问。
- agent-service 验签也要加入 nonce 重放缓存，当前只校验时间窗和签名，未阻止 5 分钟内重放。

验收标准：
- 未签名请求访问 `/internal/v1/**` 返回 401。
- 同一 nonce 重放请求返回 401。

### P0-5 Cookie 凭证缺少 CSRF 防护

证据：后端设置 HttpOnly Cookie；前端请求均使用 `credentials: include`；后端未见 CSRF token、Origin/Referer 校验或双提交 Cookie 逻辑。CORS 不是 CSRF 的完整防线。

风险：用户或管理员登录后，第三方站点可诱导浏览器发送带 Cookie 的状态变更请求，尤其影响后台配置、用户状态、算力加减、模型配置删除等接口。

修复建议：
- 对所有非 GET/HEAD/OPTIONS 请求启用 CSRF 防护。
- 最小方案：校验 `Origin`/`Referer` 必须在允许域名内，并为 Cookie 会话增加 CSRF token header。
- 管理端敏感操作二次确认不能替代 CSRF 防护。

验收标准：
- 无 `X-CSRF-Token` 或非法 `Origin` 的跨站 POST/PUT/PATCH/DELETE 返回 403。
- Bearer-only API 与 Cookie API 的安全策略清晰区分。

## P1：首版生产建议完成

### P1-1 登录、注册、短信验证码缺少全局限流与风控

证据：短信验证码仅按手机号做 60 秒冷却；登录密码校验没有按账号/IP/设备维度的失败次数限制。

风险：短信轰炸、验证码撞库、密码暴力破解、用户枚举和短信成本失控。

修复建议：
- 登录：按 IP、账号、设备指纹做滑动窗口限流和失败锁定。
- 短信：按手机号、IP、设备、场景做多维限流；增加图形验证码或行为验证。
- 统一返回“请求已受理/账号或密码错误”，避免通过错误文案枚举账号存在性。

验收标准：
- 同 IP 或同账号连续失败达到阈值后进入冷却。
- 短信接口压测无法绕过手机号以外的限流维度。

### P1-2 用户状态变更后旧 JWT 仍可继续访问

证据：`JwtTokenProvider.parseToken` 只校验签名、过期、denylist，`AuthInterceptor` 不查询用户当前状态。账号被禁用后，旧 token 在过期前仍可访问。

风险：管理员禁用用户或降权后无法立即生效；被盗 token 的处置窗口过长。

修复建议：
- 鉴权时从 Redis 缓存或数据库校验用户状态和权限版本。
- 用户禁用、角色变更、密码重置时递增 `tokenVersion` 或写入用户级 denylist。
- 缩短 access token 有效期，引入 refresh token 轮换。

验收标准：
- 禁用用户后，已有 access token 立即返回 401/403。
- 管理员降权后旧管理员 token 不能访问 `/api/admin/v1/**`。

### P1-3 模型 API Key 明文落库并经内部接口传递

证据：`agent_model_configs.api_key` 明文保存；`InternalAgentModelConfigResponse` 返回完整 `apiKey` 给内部调用方；worker execution context 中也会携带模型密钥。

风险：数据库、日志、内部网络、worker 进程任一环节泄露都会暴露模型供应商密钥，可能造成高额账单。

修复建议：
- 使用 KMS/Secret Manager 或应用层 envelope encryption 存储密钥。
- 后端只在执行时解密，避免在普通管理列表和日志中出现完整密钥。
- 分环境、分租户、分模型配置限制密钥权限和额度。

验收标准：
- 数据库中 `api_key` 字段不可直接用于调用供应商。
- 日志扫描不出现完整 API Key。

### P1-4 任务队列使用 Redis list，worker 崩溃会丢任务

证据：worker 使用 `BRPOP` 从 Redis list 取任务，取出后如果进程崩溃，消息已经从队列移除；outbox 只保证进入队列，不保证 worker ack。

风险：任务长时间不完成、用户算力被冻结、需要人工补偿；用户规模上来后会成为稳定性事故。

修复建议：
- 改为 Redis Streams consumer group、RabbitMQ、Kafka、SQS 等支持 ack/重投递的队列。
- 或使用 pending/inflight 队列加 visibility timeout 和 watchdog。
- 任务状态增加超时回收：`PROCESSING` 超过阈值自动重入队或失败释放算力。

验收标准：
- worker 在模型调用中途被 kill，任务可自动恢复或失败释放冻结算力。
- 重复投递不会重复扣费或重复插入结果。

### P1-5 Agent 文件解析存在压缩炸弹和同步阻塞风险

证据：上传限制为 10MB，但 docx 解析直接打开 zip 并读取 `word/document.xml`，无压缩比、条目大小、解析耗时限制；上传请求同步等待 agent-service 解析。

风险：构造 docx/zip bomb 可造成 CPU/内存飙升；上传接口被慢解析阻塞，影响后端线程池。

修复建议：
- 文件解析异步化，上传后进入 `PARSING`，由专门队列处理。
- 限制 zip 条目数量、单条目解压后大小、总解压大小、解析耗时。
- 只允许白名单 MIME/后缀，并做内容嗅探。

验收标准：
- 恶意压缩文件不会让 agent-service 内存或 CPU 异常增长。
- 上传接口在解析慢时仍能快速返回。

### P1-6 Agent-service 对外暴露 8090 端口

证据：`deploy/docker-compose.yml` 将 `agent-service` 映射为 `${AGENT_SERVICE_PORT:-8090}:8090`。

风险：即使签名开启，也不应让内部执行服务直接面对公网；签名实现、框架漏洞、解析接口都增加攻击面。

修复建议：
- agent-service 仅在内部 Docker network 或私有子网可达。
- 公网入口通过 API 网关/WAF 只转发用户和管理端需要的 backend 路由。

验收标准：
- 外网无法访问 `http(s)://host:8090/health` 和 `/internal/v1/**`。

## P2：规模化与运维加固

### P2-1 管理后台缺少细粒度 RBAC 与审计闭环

现状：当前仅区分 `ADMIN` 与 `USER`，后台可执行用户状态变更、算力手动增减、工具和模型配置操作。

建议：拆分超级管理员、运营、客服、财务、模型配置管理员；所有敏感操作写入不可篡改审计日志，包含 operator、IP、requestId、前后值。

### P2-2 前端仍保存 Bearer token，XSS 后可被读取

现状：管理端使用 `sessionStorage` 存 token，用户端也存在 session bearer 逻辑；同时后端又设置 HttpOnly Cookie。

建议：生产优先只用 HttpOnly Secure SameSite Cookie + CSRF token；如必须保留 Bearer，限制在内存态，不落 localStorage/sessionStorage。

### P2-3 生产观测能力不足

建议补齐：
- 统一结构化日志，脱敏手机号、token、API Key、模型请求内容。
- 指标：登录失败率、短信发送量、任务排队时长、模型调用耗时/失败率、队列积压、Redis/MySQL 连接池。
- 告警：任务失败率、队列积压、worker 心跳丢失、模型调用超时、短信异常消耗。

### P2-4 模型调用和 Agent 工具调用缺少更强的成本预算

现状：Agent 有模型调用次数和工具调用次数配置，普通 AI 工具按预估算力冻结，但模型侧实际 token/成本不回传。

建议：记录每次模型调用 provider、model、latency、input/output token、成本估算；支持按用户/租户/日额度限流。

### P2-5 SQL 迁移体系不完整

现状：SQL 文件与 `DataInitializer.ensureSchemaCompatibility` 混用，容易出现新环境和旧环境字段/索引不一致。

建议：引入 Flyway/Liquibase；禁止运行期随意 `ALTER TABLE`；每个 schema 变更有版本、回滚策略和测试。

## 建议执行路线

1. 第 1 阶段：生产阻断项  
完成 P0-1 到 P0-5，产出生产 compose/K8s、密钥策略、默认账号清理、CSRF、内部签名强制开启。

2. 第 2 阶段：公开用户准入  
完成 P1-1 到 P1-6，重点是登录/短信风控、旧 token 失效、队列可靠性、文件解析隔离、内部端口隔离。

3. 第 3 阶段：规模化运营  
完成 P2 项，包括 RBAC、审计、观测、成本治理和数据库迁移体系。

## 生产准入检查清单

- `APP_PRODUCTION_MODE=true`，缺少强 secret 时服务启动失败。
- 无默认管理员密码，无默认数据库密码。
- 公网只暴露 HTTPS 入口，MySQL/Redis/agent-service 不暴露公网。
- 所有 Cookie 状态变更接口具备 CSRF 防护。
- `/internal/v1/**` 双向均启用签名，带 nonce 防重放。
- worker 崩溃不会丢任务，超时任务会释放或重试。
- 上传解析异步化并有压缩炸弹防护。
- 登录、注册、短信验证码具备 IP/账号/设备维度限流。
- 模型密钥加密存储，日志脱敏。
- 管理员敏感操作有审计日志和最小权限。
