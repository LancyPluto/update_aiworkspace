# Admin V1 后台最小交付 · 推进记录

> 维护人：后端 Owner 艾相波 · 单一可信来源（接口契约见 `docs/api/openapi.yml`，数据库见 `sql/001_init_v1.sql`）。
> 最近一次同步：**2026-05-09 00:55（Day 2 夜间挡）**
> 关联分支：`feature/admin-web`（PR 待提）
> 阅读建议：先看「环境快速启动 SOP」「最新一日进度」，再看「同伴必看」是否影响你的模块。

---

## 环境快速启动 SOP（夜间挡补）

> 任何时候启动失败，请按这个顺序排查，今晚的所有坑都来自这两步没做对。

1. **打开 Docker Desktop**，等任务栏鲸鱼图标变 Running，确认 `docker ps` 能看到 `ai-supermarket-mysql`（3307）+ `ai-supermarket-redis`（6379）。如果容器停了：
   ```pwsh
   docker compose -f deploy/docker-compose.yml up -d
   ```
2. **后端**：`mvn -f backend/pom.xml spring-boot:run`，等到 `Started AiToolMarketApplication`。
3. **admin 前端**：`cd admin-frontend && npm run dev`，访问 `http://127.0.0.1:5174/`（已用 `strictPort: true` 锁死端口）。
4. 如果 `vite build` 报 `Cannot find module @rollup/rollup-win32-x64-msvc`（npm Windows 已知 bug），跑：
   ```pwsh
   cd admin-frontend
   npm install --no-save @rollup/rollup-win32-x64-msvc
   ```
5. 如果 5174 出 `ERR_EMPTY_RESPONSE`，说明有僵尸 vite 进程同抢端口，`netstat -ano | findstr :5174` + `taskkill /F /PID <pid>` 清掉再启。

---

## 总览（9 页面交付清单）

| # | 后台页面 | 后端接口 | 状态 |
| - | --- | --- | --- |
| 1 | 工具列表 | `GET  /api/admin/v1/tools` | ✅ 后端就绪 |
| 2 | 工具创建 / 编辑 | `POST /api/admin/v1/tools`、`PUT /api/admin/v1/tools/{toolId}` | ✅ 后端就绪 |
| 3 | 字段配置（含版本与发布） | `GET/POST /api/admin/v1/tools/{toolId}/field-schemas`、`POST /api/admin/v1/field-schemas/{schemaId}/publish`、并保留旧 `/tools/{toolId}/fields` 兼容 | ✅ 后端就绪 |
| 4 | Prompt 配置（版本 / 试运行 / 发布） | `GET/POST /api/admin/v1/tools/{toolId}/prompts`、`POST /api/admin/v1/prompts/{promptId}/versions`、`POST /api/admin/v1/prompt-versions/{id}/test-generate`、`POST /api/admin/v1/prompt-versions/{id}/publish` | ✅ 后端就绪 |
| 5 | 工具上下线 | `POST /api/admin/v1/tools/{toolId}/publish`、`POST /api/admin/v1/tools/{toolId}/offline` | ✅ 后端就绪（含发布前校验） |
| 6 | 任务列表（运营视角） | `GET /api/admin/v1/tasks?status=&toolCode=&userId=` | ✅ 后端就绪 |
| 7 | 任务详情（含日志 / 流水 / 失败原因） | `GET /api/admin/v1/tasks/{taskId}` | ✅ 后端就绪 |
| 8 | 用户列表 | `GET /api/admin/v1/users` | ✅ 后端就绪 |
| 9 | 手动加算力 | `POST /api/admin/v1/users/{userId}/credits/manual-add` | ✅ 后端就绪 |

整体进度：**后端 9/9 就绪 · 前端 9/9 联调完成（vue-tsc/vite build 通过）· 本机 smoke 已跑通 · 后台用户/算力接口测试 + 契约回归 待补**

---

## 2026-05-09 凌晨（Day 2，夜间挡）

### 今日完成（前端联调全量打通）

- `admin-frontend/src/types.ts`：与后端 `FieldSchema.fields`、`PromptVersion`、`AdminTaskDetail`、`ManualAddCreditsResult`、`TaskLog`、`CreditLogItem` 全量对齐；新增 `FieldType` 联合类型与 `AdminTaskQuery`。
- `admin-frontend/src/api/*` 重写四个 client：
  - `fieldSchemas.ts`：用 `fields` 而不是 `items`，删掉占位字段。
  - `prompts.ts`：拆 `system/userPromptTemplate/outputFormat`，`testGenerate(versionId, params)` 自动包成 `{ params }`，新增 `fetchPromptVersions`。
  - `tasks.ts`：切到 `/api/admin/v1/tasks*`，支持 `status / toolCode / userId` 过滤、`retry` 与 `cancel`。
  - `users.ts`：换成 `AdminMember` 列表 + `manualAddCredits` 真返回 `ManualAddCreditsResult`。
- 9 个页面全部替换占位逻辑，统一带上发布/校验提示文案，关键交互细节：
  - 工具列表：增加 `状态/搜索` 过滤、4 张统计卡片，发布前确认弹窗显示「需要 ACTIVE 字段 Schema + ACTIVE Prompt」提示。
  - 工具表单：编辑态 `toolCode` 只读；创建成功后弹引导跳转字段配置。
  - 字段配置：版本号自动递增 + 「基于 ACTIVE 复制」、`select` 才允许编辑 `optionsJson` 并做 JSON 合法性校验、历史 schema 可展开看字段。
  - Prompt 配置：左 Prompt 列表（卡片单选） / 中新建版本（system/user/output 三段） / 下历史版本表（试运行 + 发布按钮） / 下下试运行参数自动从 ACTIVE 字段 Schema 渲染、占位说明用 HTML 实体规避 Vue 解析。
  - 任务列表：状态/工具码/用户 ID 过滤、消耗算力列、状态相关的重试/取消按钮。
  - 任务详情：基础信息加 `userNickname / consumedCredits / errorCode / errorMessage`，时间线渲染 `ai_task_logs`，右侧表格渲染 `credit_logs`。
  - 用户与算力：4 张统计卡片（总数/管理员/普通/算力总和），手动加算力对话框带「加完后余额前后值」回显。
- 后端为 Prompt 页补一条新接口：`GET /api/admin/v1/prompts/{promptId}/versions`（已写入 OpenAPI、Mapper、Service、Controller）。
- 验证：
  - 后端：`mvn -f backend/pom.xml test` → **Tests run: 14, BUILD SUCCESS**。
  - 前端类型：`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` → 无错误。
  - 前端打包：`node node_modules/vite/bin/vite.js build` → 成功，仅警告 chunk size，不影响运行。
  - 本机端到端 smoke：admin-frontend dev (5174) + backend spring-boot:run (8080) + Docker MySQL/Redis 已起；登录 / 工具列表 / 字段 / Prompt / 任务 / 用户 7 个页面均可访问后端，**基本跑通**。
- 沿途修的两个坑（已写进「同伴必看 §3」与本节顶部 SOP）：
  - vite 僵尸进程同抢 5174 → `ERR_EMPTY_RESPONSE`：`vite.config.ts` 加 `strictPort: true`，并补 `taskkill` 清理 SOP。
  - Windows 上 npm 漏装 `@rollup/rollup-win32-x64-msvc`：补一行 `npm install --no-save` 命令。
  - 后端启动时 MySQL `Connection refused: getsockopt`：本机 Docker Desktop 挂了 → SOP 第 1 步。

### 明日（Day 3）计划

- 后端：补 `AdminUserApiTest`（list / manual-add 的成功 + 重复 + 非法 amount）+ `AdminTaskFiltersApiTest`（status/toolCode/userId 过滤、retry/cancel 状态迁移）。
- 契约：写一个最小 diff 脚本（OpenAPI examples ↔ 真实返回 JSON 字段集），跑在 CI 前置。
- 联调延伸：和 Worker 同事跑一遍真实 LLM 调用 → 任务详情 logs/creditLogs 串联。

---

## 2026-05-08（Day 1）

### 今日完成

- 对齐契约：`docs/api/openapi.yml` 已扩展字段 Schema 版本化、Prompt 版本/发布/试运行、Admin 用户列表与手动加算力、Task 详情聚合等模型与路径。
- 后端实现完整链路：DTO → Mapper → Service → Controller，并保证旧路径向后兼容。
- 工具发布前置校验：缺基础信息 / 缺 ACTIVE 字段 Schema / 缺 ACTIVE Prompt 版本时返回 `PARAM_ERROR` + 中文错误。
- 任务详情聚合：`userNickname / consumedCredits / errorCode / errorMessage / logs / creditLogs` 全量返回（仅 admin 详情聚合 logs/creditLogs，用户视图保持轻量）。
- Worker 状态写回时同步落 `ai_task_logs`，覆盖 `TASK_PROCESSING / TASK_SUCCESS / TASK_FAILED / TASK_RETRY / TASK_CANCEL / TASK_CREATED`。
- 创建工具时自动初始化默认 ACTIVE 字段 Schema + 默认 ACTIVE Prompt 版本，以保证发布校验链路连通；同时校验字段类型只允许 `text / textarea / select / number`。
- 测试库 `backend/src/test/resources/schema-test.sql` 增补 `tool_prompts / tool_prompt_versions / ai_task_logs` 三张表，使集成测试与生产 schema 对齐。
- 全量后端测试：`mvn -f backend/pom.xml test` → **Tests run: 14, Failures: 0, Errors: 0**，BUILD SUCCESS。

### 明日计划

- 接入前端 9 个页面（admin-web）：工具/字段/Prompt/任务/用户/算力，统一替换占位逻辑。
- 编写最小契约回归脚本（按 OpenAPI），并补 admin user / credit 后台接口测试。

---

## 同伴必看（Heads-up）

> 凡是你的模块用到下列接口或表，请同步关注本节。其余模块不受影响。

### 1. Worker 服务（后端 / 任务消费侧）

- ✅ **可继续使用**：`/api/internal/v1/tasks/{taskId}/execution-context`、`/processing`、`/success`、`/failed`，请求 / 返回字段未删减。
- 🆕 **行为补强**：上述四个接口在 Worker 调用成功时，会**自动写入 `ai_task_logs` 流水**（事件类型分别是 `TASK_PROCESSING / TASK_SUCCESS / TASK_FAILED`）。Worker 侧无需改造，但**请勿对该表自行写入**，避免重复。
- ⚠️ **失败回写**：`failed` 接口仍接收 `errorCode / errorMessage`；现已落库到 `ai_tasks.error_code` 与 `ai_tasks.error_message`，前后端会读取，请保证 `errorMessage` 是面向用户的可读文案，不要直接塞堆栈。
- ⚠️ **取消任务**：管理员侧会调用 `POST /api/admin/v1/tasks/{id}/cancel`，将任务状态置为 `CANCELLED`。Worker 在心跳/写回前请检查任务状态，若已 `CANCELLED` 应直接放弃写回。

### 2. 用户端前端（user-web）

- ✅ **完全兼容**：`GET /api/v1/tools/{toolCode}` 返回的 `fields[*]` 仍包含 `options`（解析后的数组）。同时新增了 `optionsJson`（原始字符串）字段，**老逻辑不需要改**。
- 🆕 **新增字段类型**：`fieldType` 现在允许 `number`，请评估表单组件是否需要适配（不适配会回退成普通输入框，不会报错）。
- ✅ 任务/算力相关用户接口未变。

### 3. 后台前端（admin-web，今晚已联调完成）

- ⚠️ **接入新路径**：字段配置和 Prompt 配置请直接使用新的 `/field-schemas`、`/prompts`、`/prompt-versions` 系列接口，**不要再扩展旧 `/tools/{id}/fields`** —— 旧 PUT 仅作为最小化兜底，不支持版本切换/历史。
- 🆕 **任务详情改为单一接口**：`GET /api/admin/v1/tasks/{id}` 一次性返回 `logs`（`ai_task_logs`）和 `creditLogs`（`credit_logs` 中 `task_id=该任务` 的流水），无需再单独拉取。
- 🆕 **手动加算力**：调用 `POST /api/admin/v1/users/{userId}/credits/manual-add`，请求体 `{ amount, reason }`；后端会同时落 `credit_accounts.balance` 与 `credit_logs(MANUAL_ADD)`。响应体含 `balanceBefore / balanceAfter`，前端会直接展示给运营。
- 🆕 **新增 Prompt 版本列表**：`GET /api/admin/v1/prompts/{promptId}/versions`（按 id 倒序），用于 Prompt 配置页展示同一 Prompt 下的全部历史版本。OpenAPI 已同步。
- ⚠️ **发布工具会做强校验**：缺类目 / 缺 ACTIVE 字段 Schema / 缺 ACTIVE Prompt 版本会返回 `PARAM_ERROR + 中文 message`，前端会把 `message` 直接 toast 出来。
- 📌 **已完成对接的 9 个页面与对应路径**（如果你白天要继续改前端，请直接接着这些文件改、不要再覆写）：
  1. `views/tools/ToolListView.vue` ↔ `GET /api/admin/v1/tools` + publish/offline。
  2. `views/tools/ToolFormView.vue` ↔ `POST/PUT /api/admin/v1/tools[/id]`。
  3. `views/tools/FieldSchemaView.vue` ↔ `/field-schemas` 全套。
  4. `views/tools/PromptConfigView.vue` ↔ `/prompts`、`/prompts/{id}/versions`、`/prompt-versions/{id}/test-generate|publish`。
  5. `views/tasks/TaskListView.vue` ↔ `GET /api/admin/v1/tasks` + retry/cancel。
  6. `views/tasks/TaskDetailView.vue` ↔ `GET /api/admin/v1/tasks/{id}` + retry/cancel。
  7. `views/users/UserListView.vue` ↔ `GET /api/admin/v1/users` + manual-add。
- 🆔 **本机 build 必要补丁**：Windows 下 npm 已知 bug 偶尔漏装 `@rollup/rollup-win32-x64-msvc`。如 `vite build` 报错找不到该模块，请先 `cd admin-frontend && npm install --no-save @rollup/rollup-win32-x64-msvc`，不要去删 `package-lock.json`。
- 🆔 **`vite.config.ts` 已加 `strictPort: true`**：防止 5174 被占时 vite 静默漂到 5175 / 5176 造成多个僵尸 vite 实例同抢端口（今晚就因此出现过 `ERR_EMPTY_RESPONSE`）。如端口冲突，会直接 EADDRINUSE 报错，请先 `taskkill /F /PID <node-pid>` 杀掉旧进程再启。
- 🆔 **dev 环境前置依赖**：后端依赖 Docker 中的 MySQL（端口 3307）+ Redis。如果后端启动报 `Communications link failure / Connection refused: getsockopt`，第一时间检查 Docker Desktop 是否在跑、`docker ps` 是否能看到 `ai-supermarket-mysql` / `ai-supermarket-redis`。

### 4. 数据库 Owner / DBA

- 📌 **未新增表 / 未变更生产 schema**：`sql/001_init_v1.sql` 早已包含 `tool_prompts / tool_prompt_versions / ai_task_logs`，本次仅在它们上增加业务读写。
- 📌 **测试库已对齐**：`backend/src/test/resources/schema-test.sql` 补齐 `tool_prompts / tool_prompt_versions / ai_task_logs`，跑 H2 集成测试不会再出现表缺失。
- 📌 **关键写入路径**：
  - `tool_field_schemas` / `tool_field_schema_items`：字段 Schema 版本，发布时同租户内仅一条 `status='ACTIVE'`。
  - `tool_prompts.active_version_id`：发布 Prompt 版本时写入指向当前 ACTIVE 版本 ID。
  - `tool_prompt_versions.published_at`：版本发布时填充。
  - `ai_task_logs`：每次任务状态迁移由后端写入，运维如需排查请用 `task_id` 过滤。
  - `credit_logs.log_type='MANUAL_ADD'`：管理员手动加算力会落此条，`operator_type='ADMIN'`、`operator_id` 为后台用户 ID。

### 5. 测试 / QA

- ⚠️ **集成测试运行方式不变**：`mvn -f backend/pom.xml test`。如本地有自维护的测试 H2 schema，请同步加 `tool_prompts / tool_prompt_versions / ai_task_logs` 三张表。
- ⚠️ **createTool 副作用**：现在创建工具会自动初始化 1 条默认字段 Schema + 1 条默认 Prompt 版本，便于后续直接 publish。如果你的用例依赖「全新工具无字段/无 Prompt」，需要显式重写。
- ⚠️ **publishTool 不再静默成功**：缺校验项时返回 400 + `PARAM_ERROR`。请使用 `errors` 提示来定位具体缺哪一项。

---

## 接口变更清单（相对昨天）

### 新增路径

| Method | Path | 描述 |
| --- | --- | --- |
| GET | `/api/admin/v1/tools/{toolId}/field-schemas` | 列出工具的全部字段 Schema 版本 |
| POST | `/api/admin/v1/tools/{toolId}/field-schemas` | 创建一个 DRAFT 字段 Schema 版本 |
| POST | `/api/admin/v1/field-schemas/{schemaId}/publish` | 把指定字段 Schema 设为 ACTIVE，原 ACTIVE 自动 INACTIVE |
| GET | `/api/admin/v1/tools/{toolId}/prompts` | 列出工具 Prompt |
| POST | `/api/admin/v1/tools/{toolId}/prompts` | 创建 Prompt（promptCode + promptName） |
| GET | `/api/admin/v1/prompts/{promptId}/versions` | 列出指定 Prompt 的全部版本（id 倒序） |
| POST | `/api/admin/v1/prompts/{promptId}/versions` | 新建 Prompt DRAFT 版本 |
| POST | `/api/admin/v1/prompt-versions/{id}/test-generate` | Prompt 版本试运行（仅渲染模板，不真实调用模型） |
| POST | `/api/admin/v1/prompt-versions/{id}/publish` | 发布 Prompt 版本，自动写 `tool_prompts.active_version_id` |
| GET | `/api/admin/v1/users` | 后台用户列表（含算力余额） |
| POST | `/api/admin/v1/users/{userId}/credits/manual-add` | 手动加算力 |

### 增量字段（向后兼容）

- `ToolField.optionsJson`：与 `options` 同义，前者为原始字符串，便于运营复制。
- `ToolField.fieldType`：新增枚举 `number`。
- `TaskDetail.userNickname / consumedCredits / errorCode / errorMessage / logs / creditLogs`：详情接口直接附带，**仅 admin 详情会非空填充 logs/creditLogs**，用户详情仍然空数组，避免泄露。

### 行为变更

- `POST /api/admin/v1/tools/{toolId}/publish` 加入发布前置校验，缺项返回 `PARAM_ERROR`。
- `POST /api/admin/v1/tools` 创建后自动初始化默认字段 Schema + 默认 Prompt 版本。
- 内部 Worker 三个写回接口同步落 `ai_task_logs`。

---

## 风险与待确认

- 🟡 **Prompt 试运行**：当前 `test-generate` 只做模板渲染（`{{key}}` 占位替换）+ 前缀 `[TEST PREVIEW]`，**未真实调用模型**。如需真实生成，需另行接入模型适配层。
- 🟡 **任务取消**：目前仅置任务状态为 `CANCELLED`，**未退还冻结/已扣算力**，待与产品确认是否需要回退策略。
- 🟡 **OpenAPI 与代码契约校验**：尚未自动化，明天补一份契约回归脚本（diff openapi.yml 与运行时返回结构）。
