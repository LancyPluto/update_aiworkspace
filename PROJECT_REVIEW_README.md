# AI Tool Market - 项目审查报告

> 审查日期：2026-05-08
> 基于 commit: `b23facd4740d15559b52d88801993d56331d6003`

---

## 一、项目概述

**AI Tool Market**（AI 工具超市）是一个让管理员配置和发布 AI 工具、用户选择工具提交任务、Worker 调用 AI 模型生成结果的平台。当前为 **V1 最小可演示版本**，正处于 7 天冲刺开发中。

项目 GitHub：`git@github.com:AI-miniLab/ai-tool-market.git`

---

## 二、当前实现的功能

### 2.1 已完成的核心功能


| 功能模块              | 说明                    | API 端点                                              |
| ----------------- | --------------------- | --------------------------------------------------- |
| **健康检查**          | 检测 MySQL、Redis、后端服务状态 | `GET /api/health`                                   |
| **用户注册**          | 普通用户注册（bcrypt 密码加密）   | `POST /api/v1/auth/register`                        |
| **用户登录**          | 用户名 + 密码登录，返回 JWT     | `POST /api/v1/auth/login`                           |
| **用户信息**          | 获取当前登录用户个人信息          | `GET /api/v1/users/me`                              |
| **管理员登录**         | 仅限 ADMIN 角色的登录        | `POST /api/admin/v1/auth/login`                     |
| **管理工具 CRUD**     | 创建、编辑、查询工具            | `GET/POST/PUT /api/admin/v1/tools`                  |
| **工具发布/下线**       | 管理员上架/下架工具            | `POST /api/admin/v1/tools/{id}/publish`             |
| **工具分类查询**        | 获取活跃的分类列表             | `GET /api/v1/tool-categories`                       |
| **公开工具查询**        | 用户查看已上线的工具列表和详情       | `GET /api/v1/tools`                                 |
| **创建 AI 任务**      | 用户选定工具提交参数，创建任务       | `POST /api/v1/tasks`                                |
| **任务状态查询**        | 用户查询自己任务的实时状态         | `GET /api/v1/tasks/{id}/status`                     |
| **任务详情**          | 查看任务输入参数和生成结果         | `GET /api/v1/tasks/{id}`                            |
| **任务列表**          | 用户查看自己的所有任务           | `GET /api/v1/tasks`                                 |
| **算力账户查询**        | 查看积分余额和消费记录           | `GET /api/v1/credits/account`                       |
| **Worker 取任务上下文** | Worker 获取任务的参数和字段配置   | `GET /api/internal/v1/tasks/{id}/execution-context` |
| **Worker 报告处理中**  | Worker 更新任务为处理中状态     | `POST /api/internal/v1/tasks/{id}/processing`       |
| **Worker 报告成功**   | Worker 提交 AI 生成结果     | `POST /api/internal/v1/tasks/{id}/success`          |
| **Worker 报告失败**   | Worker 报告任务失败及错误信息    | `POST /api/internal/v1/tasks/{id}/failed`           |


### 2.2 数据库表（共 16 张）


| 表名                        | 用途                                                   |
| ------------------------- | ---------------------------------------------------- |
| `users`                   | 用户表（支持 USER / ADMIN 两种角色）                            |
| `roles`                   | 角色定义表（预置 USER, ADMIN）                                |
| `user_roles`              | 用户-角色关联表                                             |
| `login_logs`              | 登录日志                                                 |
| `tool_categories`         | 工具分类（预置 Copywriting）                                 |
| `ai_tools`                | AI 工具主表（支持草稿/上线/下线状态）                                |
| `tool_field_schemas`      | 工具字段模式版本                                             |
| `tool_field_schema_items` | 工具字段定义（text/textarea/select）                         |
| `tool_prompts`            | 工具 Prompt 配置                                         |
| `tool_prompt_versions`    | Prompt 版本管理（支持 system_prompt / user_prompt_template） |
| `ai_tasks`                | AI 任务主表（支持幂等、重试、完整状态机）                               |
| `ai_task_inputs`          | 任务输入参数明细                                             |
| `ai_task_logs`            | 任务操作日志（完整的事件溯源）                                      |
| `ai_result_resources`     | 任务结果资源存储                                             |
| `credit_accounts`         | 用户算力账户（余额/冻结/已消耗）                                    |
| `credit_logs`             | 算力变更流水日志                                             |
| `worker_heartbeats`       | Worker 心跳记录                                          |
| `admin_operation_logs`    | 管理员操作审计日志                                            |


> **说明**：数据库设计为全功能版本，但当前代码实际仅使用了 `users`、`tool_categories`、`ai_tools`、`tool_field_schemas`、`tool_field_schema_items`、`ai_tasks`、`ai_result_resources`、`credit_accounts`、`credit_logs` 等核心表。

### 2.3 技术栈


| 模块     | 技术                              | 版本    |
| ------ | ------------------------------- | ----- |
| 语言     | Java                            | 17    |
| 框架     | Spring Boot                     | 3.3.5 |
| 数据库    | MySQL (开发/生产) + H2 (测试)         | 8.0   |
| 缓存/队列  | Redis                           | 7     |
| 认证     | 自实现 JWT（HS256 + HMAC-SHA256）    | -     |
| 密码加密   | Spring Security Crypto (BCrypt) | -     |
| 数据访问   | Spring JDBC (`JdbcTemplate`)    | -     |
| API 文档 | OpenAPI 3.0 YAML                | -     |
| 部署     | Docker Compose                  | -     |
| 构建工具   | Maven                           | -     |


---

## 三、开发范式分析

### 3.1 架构分层

```
Controller → Service (接口) → ServiceImpl (实现) → Mapper (持久化)
```

- **Controller 层**：接收请求，调用 Service，返回 `ApiResponse<T>` 统一响应
- **Service 层**：接口 + 实现分离（Interface + Impl 模式）
- **Mapper 层**：直接使用 `JdbcTemplate` + `RowMapper` 手写 SQL（`@Repository`）

### 3.2 包结构（按功能分包）

```
com.aiminilab.aitoolmarket
├── auth/          # 认证模块（登录注册、JWT、AuthContext）
├── admin/         # 管理员模块（骨架，尚未实现）
├── common/        # 公共模块（枚举、DTO、异常处理、Controller 基类）
├── config/        # 配置类（CORS、拦截器、数据初始化、Bean）
├── credit/        # 算力模块（账户、扣费、流水）
├── internal/      # 内部 API（Worker 调用，骨架）
├── task/          # 任务模块（创建、状态、结果、Worker 交互）
├── tool/          # 工具模块（CRUD、分类、字段配置）
└── user/          # 用户模块（实体、Mapper）
```

**每个子包内部**：`controller/` → `service/` → `mapper/` → `entity/` → `dto/`

### 3.3 关键设计决策


| 决策                  | 说明                                                                            |
| ------------------- | ----------------------------------------------------------------------------- |
| **无 ORM**           | 使用 `JdbcTemplate` 替代 JPA / MyBatis，手写全部 SQL，精确控制查询                            |
| **接口 + Impl**       | Service 层接口与实现分离，保留扩展性（部分模块尚未实现 Impl 类）                                       |
| **记录式 DTO**         | 大量使用 Java 16+ `record` 类型（LoginRequest、ApiResponse、PageResponse 等）            |
| **ThreadLocal 上下文** | `AuthContext` 使用 `ThreadLocal<AuthUser>` 存储登录用户信息，在请求拦截器中设置和清理                |
| **自定义 JWT**         | 未使用 Spring Security / OAuth2，纯手动实现 JWT（HMAC-SHA256 签名 + Base64 URL 编码）        |
| **统一响应格式**          | 所有 API 返回 `ApiResponse<T>`（code + message + data + requestId）                 |
| **全局异常处理**          | `@RestControllerAdvice` + `GlobalExceptionHandler`，业务异常使用 `BusinessException` |
| **无 @Autowired**    | 全部使用构造器注入                                                                     |
| **测试用 H2**          | 集成测试使用 H2 内存数据库 + MySQL 兼容模式                                                  |


### 3.4 任务状态机

```
CREATED → QUEUED → PROCESSING → SUCCESS
                      ↓
                   FAILED / TIMEOUT / CANCELLED
```

目前代码实际创建任务时直接写入 `QUEUED` 状态，`CREATED` 状态保留但未使用。

### 3.5 工具状态机

```
DRAFT → ONLINE ↔ OFFLINE
```

### 3.6 安全设计

- 基于 `AuthInterceptor` 拦截 `/api/**` 路径
- 公开路径白名单：健康检查、认证接口、工具广场、Internal API
- 管理员接口通过请求路径前缀 `/api/admin/v1/` + `userType` 判断权限
- JWT 使用常量时间比较防止时序攻击

---

## 四、已实现的测试


| 测试类                     | 覆盖场景                                     | 行数  |
| ----------------------- | ---------------------------------------- | --- |
| `AuthApiTest`           | 注册/登录/获取用户信息；管理员 vs 普通用户权限隔离；重复注册/密码错误拒绝 | 168 |
| `ToolApiTest`           | 管理员创建/编辑/发布/下架工具；用户端按状态查询工具详情            | 119 |
| `TaskCreditApiTest`     | 用户创建任务、扣减算力；余额不足拒绝；查询任务状态/详情/列表          | 169 |
| `WorkerInternalApiTest` | Worker 获取上下文、报告处理中/成功/失败；端到端验证流程         | 174 |


测试使用 `@SpringBootTest` + `@AutoConfigureMockMvc` + H2 内存数据库，每条测试类使用独立的数据库实例（隔离性良好）。但**未测试以下边界场景**：

- 管理员查看全部工具（含草稿/已下线）
- 用户查看非自己任务的权限隔离
- 任务幂等性（同 `clientRequestId`）

---

## 五、当前未实现的部分

### 5.1 前端（完全缺失）

项目计划包含两个 Vue 3 前端（`user-web` 用户端、`admin-web` 管理后台）和一个 Python Worker，当前仓库中 **完全没有**：

- `user-web/` — 用户端 Vue 应用
- `admin-web/` — 管理后台 Vue 应用
- `worker/` — Python AI Worker

### 5.2 后端待实现


| 功能         | 说明                                                  |
| ---------- | --------------------------------------------------- |
| 管理员工具字段配置  | `tool_field_schema_items` 的增删改查未实现                  |
| Prompt 配置  | `tool_prompts` 和 `tool_prompt_versions` 表已建但无 API   |
| 任务重试       | `ai_tasks.retry_count` 和 `max_retry_count` 列存在但未使用  |
| Worker 心跳  | `worker_heartbeats` 表已建但无对应 API                     |
| 管理员操作日志    | `admin_operation_logs` 表已建但未记录                      |
| 登录日志       | `login_logs` 表已建但未写入                                |
| Redis 消息队列 | 配置了 Redis 依赖但未实现任务队列出队/入队逻辑                         |
| 用户角色关联     | `user_roles` + `roles` 表已建但代码中通过 `user_type` 字段判断角色 |
| 用户信息更新     | 无修改密码/更新资料的 API                                     |
| 分页/排序      | `PageResponse` 已定义但未实现真正的分页查询                       |


### 5.3 V1 不做的功能

参见 README：支付、套餐、算力审批流、RBAC、监控告警、文件上传、素材库、多模态、Prompt 回滚、多租户。

---

## 六、项目亮点

1. **表设计完善**：16 张表覆盖了工具配置、任务编排、算力计费、审计日志等完整场景，字段注释清晰
2. **API 设计规范**：符合 RESTful 风格，统一响应格式，OpenAPI 文档详尽（1314 行）
3. **测试覆盖率高**：4 个集成测试类覆盖了从认证、工具管理到任务 Worker 交互的完整链路
4. **安全设计到位**：自定义 JWT 有时序攻击防护、常量时间比较、密码 bcrypt 加密
5. **数据库迁移友好**：`sql/` 目录下有完整初始化脚本，Docker Compose 自动挂载执行
6. **配置外部化**：所有环境变量通过 `${}` 占位符读取，`.env.example` 提供模板

---

## 七、小白启动指南

> 以下步骤适合**第一次接触这个项目**的开发者，无需了解 Spring Boot 细节即可启动运行。

### 7.1 环境要求


| 工具     | 版本要求 | 检查命令               |
| ------ | ---- | ------------------ |
| JDK    | 17+  | `java -version`    |
| Maven  | 3.8+ | `mvn -version`     |
| Docker | 20+  | `docker --version` |
| Git    | 任意   | `git --version`    |


### 7.2 快速启动（5 分钟）

```bash
# 1. 克隆项目
git clone git@github.com:AI-miniLab/ai-tool-market.git
cd ai-tool-market

# 2. 启动基础设施（MySQL + Redis）
docker compose -f deploy/docker-compose.yml up -d

# 3. 复制环境变量（无需修改即可本地运行）
copy .env.example .env

# 4. 编译并运行后端
cd backend
mvn clean compile -q
mvn spring-boot:run
```

### 7.3 验证启动成功

```bash
# 健康检查（应该返回 MySQL ok + Redis ok）
curl http://localhost:8080/api/health

# 预期返回：
# {"code":"SUCCESS","message":"ok","data":{"service":"backend","mysql":"ok","redis":"ok"},"requestId":null}

# Ping 测试
curl http://localhost:8080/api/v1/ping
# 预期返回：{"code":"SUCCESS","message":"ok","data":{"scope":"user","status":"ok"},...}
```

### 7.4 测试初始账号


| 角色   | 账号      | 密码       |
| ---- | ------- | -------- |
| 管理员  | `admin` | `123456` |
| 普通用户 | `user1` | `123456` |


> 系统启动时会自动创建上述初始用户。

### 7.5 常见问题

**Q: `mvn spring-boot:run` 启动报端口被占用？**

```bash
# 修改端口（默认 8080）
set SERVER_PORT=8081
mvn spring-boot:run
```

**Q: Docker 启动 MySQL/Redis 失败？**

```bash
# 查看容器日志
docker logs ai-supermarket-mysql
docker logs ai-supermarket-redis

# 重启容器
docker compose -f deploy/docker-compose.yml restart
```

**Q: 数据库连接失败？**

- 确保 Docker 容器已启动：`docker ps`
- MySQL 映射端口为 `3307`（非默认 3306），已配置在 `.env.example` 中

**Q: 没有 Docker 怎么办？**

- 可以本地安装 MySQL 8.0+ 和 Redis 7+，然后修改 `.env` 中的连接地址和端口

---

## 八、自动化测试

### 8.1 运行全部测试

```bash
cd backend
mvn clean test
```

### 8.2 运行单个测试类

```bash
# 认证测试
mvn test -Dtest=AuthApiTest

# 工具管理测试
mvn test -Dtest=ToolApiTest

# 任务 + 算力测试
mvn test -Dtest=TaskCreditApiTest

# Worker 内部 API 测试
mvn test -Dtest=WorkerInternalApiTest
```

### 8.3 一键测试脚本

项目根目录提供了 `run-tests.bat`（Windows）和 `run-tests.sh`（Linux/Mac）脚本，功能包括：

1. 检查 JDK 和 Maven 环境
2. 自动编译项目
3. 运行所有集成测试
4. 生成测试报告摘要
5. 测试结果以彩色输出（通过 ✓ / 失败 ✗）

### 8.4 测试架构说明

- **框架**：JUnit 5 + Spring Boot Test + MockMvc
- **数据库**：H2 内存数据库（MySQL 兼容模式）
- **隔离性**：每个测试类使用独立的数据库实例（`DB_CLOSE_DELAY=-1` + 唯一 `mem` 名称）
- **无需外部依赖**：测试不需要 Docker、不需要真实的 MySQL/Redis

---

## 九、改进建议

1. **引入 MyBatis 或 JPA**：纯 JdbcTemplate + 手写 RowMapper 在字段增多时维护成本高
2. **日志记录**：缺少操作日志和登录日志的实际写入代码
3. **消息队列**：需要实现 Redis List / Stream 的任务队列推送和 Worker 消费逻辑
4. **参数校验增强**：目前仅对少量字段使用 `@Valid`，部分 JSON 处理未做异常兜底
5. **分页查询**：目前全部查全量数据回 List，后续需支持 `LIMIT/OFFSET`
6. **空包清理**：`admin/`、`internal/` 下仅有 `.gitkeep` 文件，建议移除或填充实现
7. **枚举集权**：部分枚举定义在 `common/enums`，部分以字符串形式散落各处，建议统一

