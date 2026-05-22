# PPT 生成工具接入 — 后端开发文档

> **版本**：2.0  
> **更新日期**：2026-05-22  
> **读者**：后端开发、运维  
> **原则**：**增量接入，不改动现有任务型工具主链路**

---

## 1. 文档目标

将开源项目 `banana-slides`（PPT 生成引擎）接入 AI 工具超市，实现：

1. 保留 banana 原有项目流程（创建 → 大纲 → 描述 → 图片 → 导出）。
2. 用户鉴权、算力、项目归属、审计统一由 `backend` 管理。
3. **不修改** 现有 `ai_tasks`、Redis 队列、`worker`、标准工具 API 的行为。

---

## 2. 不影响原有功能的硬性边界

### 2.1 禁止改动的模块（只读依赖）

| 模块 | 路径/接口 | 说明 |
| --- | --- | --- |
| 任务创建 | `TaskServiceImpl`、`POST /api/v1/tasks` | 标准工具继续走此链路 |
| Worker 回调 | `InternalTaskController`、`/api/internal/v1/tasks/*` | 不变 |
| Redis 队列 | `TaskQueuePublisher`、`ai:task:queue` | PPT **不入队** |
| Worker | `worker/task_queue/redis_consumer.py` | 不新增 PPT handler |
| 工具查询 | `ToolController`、`GET /api/v1/tools` | 仅**扩展**响应字段，不删不改既有字段 |
| 管理端工具 CRUD | `AdminToolController`、`/api/admin/v1/tools` | 继续服务所有工具；PPT 仅多一种 `configNote` 约定 |
| Agent | `agent/*` | 与 PPT 无关，不耦合 |

### 2.2 允许且必须的「仅增量」改动

| 类型 | 说明 |
| --- | --- |
| **新增** Java 包 `com.aiminilab.aitoolmarket.ppt` | Controller / Service / Client / Entity / Mapper |
| **新增** 表 `ppt_project_bindings`、`ppt_step_billing_logs` | 不修改 `ai_tasks` 表结构 |
| **新增** API 前缀 `/api/v1/ppt/*` | 与 `/api/v1/tasks` 并列，互不替代 |
| **新增** 配置 `ppt.engine.*` | `application.yml`，默认值不影响其他模块 |
| **新增** 枚举/错误码 | `ErrorCode.PPT_*`、`CreditSourceType.PPT_STEP`（见 §5.3） |
| **可选扩展** `ToolDetailResponse` | 增加 `workflow` 字段，供前端解析，旧客户端可忽略 |

### 2.3 回归验证清单（发版前必跑）

- [ ] `POST /api/v1/tasks` + 小红书等现有工具全流程正常
- [ ] Worker `execution-context` / `success` / `failed` 回调正常
- [ ] `GET /api/v1/tools` 列表与详情对**非 PPT** 工具响应不变
- [ ] 管理端 `GET/POST/PUT /api/admin/v1/tools` 对现有工具正常
- [ ] Agent 会话与 Run 不受影响
- [ ] 未配置 PPT 工具时，系统行为与接入前一致

---

## 3. 推荐架构

```text
user-web / admin-frontend
        │
        ▼
backend (Spring Boot)
  ├── 既有：auth / tool / task / credit / agent  （不改动行为）
  └── 新增：ppt 模块（BFF）
        ├── PptWorkflowService      读 ai_tools 工作流配置
        ├── PptProjectService     binding + 鉴权
        ├── PptBillingService     按步骤 freeze/settle/release
        └── PptEngineClient       HTTP → banana-slides
                │
                ▼
banana-slides (Flask, 建议 engines/banana-slides, 内网 :5000)
```

**不在 backend 内重写 banana 业务逻辑**；仅做代理、映射、计费、审计。

---

## 4. 引擎侧（banana-slides）约定

### 4.1 部署

- 服务地址：`ppt.engine.base-url`（如 `http://banana-slides:5000`）
- **禁止** 对公网暴露；仅 BFF 内网访问
- 建议目录：`ai-tool-market/engines/banana-slides/`（与原仓库同版代码）

### 4.2 引擎 API（BFF 转发对照）

BFF 根据 `binding.banana_project_id` 转发，路径与 banana 一致：

| 能力 | Banana 方法 | 说明 |
| --- | --- | --- |
| 创建项目 | `POST /api/projects` | body: `creation_type`, `idea_prompt` 等 |
| 项目详情 | `GET /api/projects/{id}` | 含 `pages`、`status` |
| 删除项目 | `DELETE /api/projects/{id}` | |
| 上传模板 | `POST /api/projects/{id}/template` | multipart |
| 生成大纲 | `POST /api/projects/{id}/generate/outline` | 同步完成 |
| 流式大纲 | `POST /api/projects/{id}/generate/outline/stream` | SSE，P1 |
| 生成描述 | `POST /api/projects/{id}/generate/descriptions` | 202 + `task_id` |
| 生成图片 | `POST /api/projects/{id}/generate/images` | 202 + `task_id` |
| 任务状态 | `GET /api/projects/{id}/tasks/{task_id}` | |
| 改大纲/描述 | `PUT .../pages/{pageId}/outline|description` | |
| 润色 | `POST .../refine/outline|descriptions` | |
| 单页生成 | `POST .../pages/{pageId}/generate/description|image` | |
| 翻新 | `POST /api/projects/renovation` | multipart |
| 导出 PPTX | `GET .../export/pptx` | 返回 `download_url` |
| 导出 PDF/图片 | `GET .../export/pdf`、`.../export/images` | P1 |

集成测试参考：`banana-slides/backend/tests/integration/test_api_full_flow.py`

### 4.3 模型与 AI 配置

第一期 **仍由 banana 自身管理**（环境变量 / `GET|PUT /api/settings`），**不** 强行写入超市 `agent_model_configs`，避免影响现有模型配置逻辑。

---

## 5. 数据模型

### 5.1 新表：`ppt_project_bindings`

```sql
CREATE TABLE ppt_project_bindings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  banana_project_id VARCHAR(64) NOT NULL,
  creation_type VARCHAR(32) NOT NULL,
  title VARCHAR(255) NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_banana_project (banana_project_id),
  KEY idx_user_tool (user_id, tool_id),
  KEY idx_user_updated (user_id, updated_at)
);
```

| 字段 | 说明 |
| --- | --- |
| `user_id` | 超市用户，所有接口必须校验 |
| `tool_id` | 对应 `ai_tools.id`（`tool_code=banana_ppt_generator`） |
| `banana_project_id` | 引擎侧 `project_id`（UUID） |
| `status` | 镜像引擎 `project.status`，便于列表查询 |

### 5.2 新表：`ppt_step_billing_logs`（建议）

```sql
CREATE TABLE ppt_step_billing_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  step_code VARCHAR(64) NOT NULL,
  credits_charged INT NOT NULL,
  credit_log_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_binding (binding_id),
  KEY idx_user_created (user_id, created_at)
);
```

### 5.3 扩展现有枚举（增量）

**`CreditSourceType`** 新增（不修改 `TASK`、`AGENT_RUN` 语义）：

```java
PPT_STEP  // sourceId = bindingId 或 billingLogId，团队内约定一种即可
```

**`ErrorCode`** 新增（同步 `user-web/src/api/types.ts`）：

| code | 场景 |
| --- | --- |
| `PPT_PROJECT_NOT_FOUND` | binding 不存在或无权 |
| `PPT_STEP_DISABLED` | 管理端关闭该步骤 |
| `PPT_ENGINE_ERROR` | 引擎 HTTP/业务失败 |
| `PPT_TASK_FAILED` | 引擎子任务 FAILED |
| `PPT_EXPORT_FAILED` | 导出失败 |

复用既有：`CREDIT_NOT_ENOUGH`、`TOOL_OFFLINE`、`UNAUTHORIZED`

### 5.4 迁移与测试 Schema

必须同步：

- `sql/0xx_ppt_bindings.sql`（新建）
- `backend/src/main/java/.../config/DataInitializer.java`（`ensureTable`）
- `backend/src/test/resources/schema-test.sql`

**不要** 修改 `ai_tasks`、`ai_result_resources` 表结构。

---

## 6. 工作流配置（管理端 → 后端）

工作流配置挂在 **`ai_tools.config_note`**，不新建工具体系。

### 6.1 标记格式

与现有 Chat 的 `<!-- ai-tool-ui:... -->` 并列，使用：

```text
<!-- ppt-workflow:{JSON} -->
```

### 6.2 JSON Schema（建议）

```json
{
  "integrationMode": "PPT_WORKSPACE",
  "customUiRoute": "/tools/banana_ppt_generator/workspace",
  "engine": {
    "baseUrl": "http://banana-slides:5000"
  },
  "creationTypes": ["idea", "outline", "descriptions", "ppt_renovation"],
  "steps": [
    { "code": "CREATE", "name": "创建项目", "credits": 5, "enabled": true },
    { "code": "OUTLINE", "name": "生成大纲", "credits": 10, "enabled": true },
    { "code": "DESCRIPTIONS", "name": "生成描述", "credits": 20, "enabled": true },
    { "code": "IMAGES", "name": "生成图片", "credits": 50, "enabled": true },
    { "code": "EXPORT_PPTX", "name": "导出 PPTX", "credits": 5, "enabled": true },
    { "code": "EXPORT_PDF", "name": "导出 PDF", "credits": 5, "enabled": false }
  ],
  "features": {
    "streamOutline": false,
    "streamDescriptions": false,
    "renovation": true,
    "singlePageRegenerate": true,
    "refineOutline": true,
    "refineDescriptions": true
  }
}
```

`PptWorkflowService` 解析逻辑：

1. 从 `tool_code` 加载 `AiTool`
2. 解析 `config_note` 中 `ppt-workflow` 块
3. 解析失败时：PPT 工具拒绝创建步骤（`SYSTEM_ERROR`），**不影响** 其他工具

### 6.3 工具种子数据（`ai_tools`）

| 字段 | 值 |
| --- | --- |
| `tool_code` | `banana_ppt_generator` |
| `tool_name` | AI PPT 生成器 |
| `tool_type` | `IMAGE_GENERATION`（或扩展 `PPT_GENERATION`） |
| `input_modality` | `MULTIMODAL` |
| `output_modality` | `FILE` |
| `execution_handler` | 占位 `TEXT_GENERATION`，**不会被 Worker 使用** |
| `estimated_credit_cost` | 各步 credits 之和（展示用） |
| `status` | `ONLINE` / `DRAFT` |
| `model_config_id` | 可 NULL |

**不要** 为该工具配置复杂 `tool_field_schema`（用户端不走 DynamicForm）。

---

## 7. 配置项

`application.yml`（或环境变量）：

```yaml
ppt:
  engine:
    base-url: ${PPT_ENGINE_BASE_URL:http://banana-slides:5000}
    connect-timeout-ms: 5000
    read-timeout-ms: 300000
  billing:
    # 默认值；可被 config_note 中 steps 覆盖
    create-project-credits: 5
    generate-outline-credits: 10
    generate-descriptions-credits: 20
    generate-images-credits: 50
    export-pptx-credits: 5
    export-pdf-credits: 5
```

`config_note` 中某步 `credits` 优先于 yaml 默认值。

---

## 8. API 设计（BFF）

统一前缀：**`/api/v1/ppt`**  
鉴权：与 `POST /api/v1/tasks` 相同，需用户 JWT（`AuthInterceptor`）。

统一响应：`ApiResponse<T>`（`code` / `message` / `data`）。

### 8.1 项目管理

#### `POST /api/v1/ppt/projects`

创建 binding + 调引擎创建项目。

请求：

```json
{
  "creationType": "idea",
  "ideaPrompt": "创建一份关于人工智能基础的 8 页 PPT",
  "imageAspectRatio": "16:9"
}
```

处理顺序：

1. 校验工具 `banana_ppt_generator` 已 ONLINE
2. 校验 `creationType` 在 workflow.creationTypes 内
3. `freeze` 步骤 `CREATE`
4. `POST` 引擎 `/api/projects`
5. 插入 `ppt_project_bindings`
6. `settle`；失败则 `release` 并删除引擎项目（若已创建）

响应：

```json
{
  "code": "SUCCESS",
  "data": {
    "bindingId": 10001,
    "projectId": "uuid-from-banana",
    "status": "DRAFT"
  }
}
```

#### `GET /api/v1/ppt/projects`

当前用户的 PPT 项目列表（查 binding 表，可选合并引擎摘要）。

#### `GET /api/v1/ppt/projects/{bindingId}`

详情：binding 校验 → 代理 `GET /api/projects/{bananaProjectId}` → 规范化返回（见 §8.4）。

#### `DELETE /api/v1/ppt/projects/{bindingId}`

删除 binding + 代理引擎删除。

#### `POST /api/v1/ppt/projects/renovation`

`multipart/form-data`，字段 `file` 等透传；创建 binding 后返回 `bindingId`、`taskId`。

### 8.2 流程接口

| 方法 | BFF 路径 | step_code | 引擎 |
| --- | --- | --- | --- |
| POST | `.../template` | — | 上传模板（可按需计费） |
| POST | `.../generate/outline` | `OUTLINE` | 同步 |
| POST | `.../generate/descriptions` | `DESCRIPTIONS` | 202 + taskId |
| POST | `.../generate/images` | `IMAGES` | 202 + taskId |
| GET | `.../tasks/{taskId}` | — | 轮询 |

流式接口（P1）：`.../generate/outline/stream`、`.../generate/descriptions/stream`  
BFF 以 **SSE 透传** 或终止于网关层，需关闭缓冲。

### 8.3 页面编辑

| 方法 | BFF 路径 |
| --- | --- |
| PUT | `.../pages/{pageId}/outline` |
| PUT | `.../pages/{pageId}/description` |
| POST | `.../pages/{pageId}/generate/description` |
| POST | `.../pages/{pageId}/generate/image` |
| POST | `.../pages/{pageId}/edit/image` |
| POST | `.../refine/outline` |
| POST | `.../refine/descriptions` |

单页重生成是否计费：由 `features` + 产品决定；建议 P1 暂不计费或计半额。

### 8.4 导出与文件

#### 导出

| 方法 | 路径 | step_code |
| --- | --- | --- |
| GET | `.../export/pptx` | `EXPORT_PPTX` |
| GET | `.../export/pdf` | `EXPORT_PDF` |
| GET | `.../export/images` | — |

响应示例：

```json
{
  "code": "SUCCESS",
  "data": {
    "downloadUrl": "/api/v1/ppt/files/10001/exports/xxx.pptx"
  }
}
```

**禁止** 直接返回引擎的 `/files/...` 给浏览器。

#### 文件代理

`GET /api/v1/ppt/files/{bindingId}/**`

1. 校验 binding 归属
2. 从引擎拉取或读引擎文件流
3. 设置正确 `Content-Type`

### 8.5 扩展：用户工具详情（可选，推荐）

`GET /api/v1/tools/{toolCode}` 在 `toolCode=banana_ppt_generator` 时，`data` 增加：

```json
{
  "workflow": { /* 解析后的对象，供 user-web 使用 */ }
}
```

实现方式：在 `ToolServiceImpl.userToolDetail` 末尾 **if** 判断 toolCode，调用 `PptWorkflowService`，**不改变** 其他工具返回结构。

---

## 9. 核心服务实现要点

### 9.1 包结构

```text
com.aiminilab.aitoolmarket.ppt/
  config/PptEngineProperties.java
  entity/PptProjectBinding.java
  entity/PptStepBillingLog.java
  mapper/PptProjectBindingMapper.java
  mapper/PptStepBillingLogMapper.java
  dto/*.java
  service/PptWorkflowService.java
  service/PptProjectService.java
  service/PptBillingService.java
  service/PptEngineClient.java
  controller/PptProjectController.java
  controller/PptFileController.java
```

### 9.2 `PptProjectService`

```java
// 伪代码
Binding requireBinding(Long bindingId, Long userId);
String resolveBananaProjectId(Long bindingId, Long userId);
void syncBindingStatus(Long bindingId, String engineStatus);
```

### 9.3 `PptBillingService`

复用 `CreditService.freeze/settle/release`：

```java
void chargeStep(Long userId, Long bindingId, String stepCode, Runnable action);
```

逻辑：

1. `workflow` 取 step.credits、enabled
2. `enabled=false` → `PPT_STEP_DISABLED`
3. `freeze` → 执行 action → 成功 `settle`，异常 `release`
4. 写 `ppt_step_billing_logs`

### 9.4 `PptEngineClient`

- 使用 `RestTemplate` 或 `WebClient`
- multipart 使用 `MultipartBodyBuilder`
- 统一解析引擎 `{ success, data, message }`
- 超时：connect 5s，read 300s（生图）

### 9.5 与 `TaskService` 的关系

**禁止** 在 `PptProjectController` 中调用 `TaskService.create()`。  
若未来做「一键全自动」，可单独提供 `POST /api/v1/ppt/projects/{id}/run-all`，内部顺序调引擎，**仍不** 进入 Redis。

---

## 10. 管理端相关后端（增量）

现有 `AdminToolController` **保持不变**。可选增量：

### 10.1 `GET /api/admin/v1/tools/{toolId}/ppt-workflow`

返回解析后的 workflow JSON，供管理端表单编辑（P1）。

### 10.2 `PUT /api/admin/v1/tools/{toolId}/ppt-workflow`

校验 steps / creationTypes 后写回 `config_note`（保留人工说明文字 + `ppt-workflow` 块）。

### 10.3 `GET /api/admin/v1/ppt/engine-health`

请求 `ppt.engine.base-url/health`，给运营自检。

以上接口均 **新增**，不修改现有 admin tool PUT 的必填校验逻辑（避免影响其他工具保存）。

---

## 11. 实施阶段

| 阶段 | 后端交付 | 验收 |
| --- | --- | --- |
| P0 | 表 + Properties + Client + 创建/详情/大纲/描述/图片/任务轮询/导出 PPTX + 计费 | Postman 全链路；回归 §2.3 |
| P1 | 翻新、单页、refine、文件代理、PDF 导出、billing 日志 | |
| P2 | Admin ppt-workflow API、引擎健康检查 | |
| P3 | SSE 透传、可选 run-all | |

---

## 12. 测试建议

### 12.1 单元测试

- `PptWorkflowService` 解析 configNote
- `PptProjectService` 越权：用户 A 不能访问用户 B 的 bindingId

### 12.2 集成测试

- `@SpringBootTest` + MockWebServer 模拟 banana
- 或 Testcontainers + 真实 banana（CI 可选）

### 12.3 回归

每次 PR 跑：`mvn test` 全量；重点 `TaskApiTest`、`ToolApiTest`、`WorkerInternalApiTest`。

---

## 13. OpenAPI

实现后补充 `docs/api/openapi.yml` 中 `paths`：

- `/api/v1/ppt/projects`
- `/api/v1/ppt/projects/{bindingId}`
- …

**不要** 修改现有 `/api/v1/tasks` 的 schema 语义。

---

## 14. 相关文档

- 用户端实现：`docs/PPT生成工具接入-前端开发文档.md`
- 架构边界：`docs/系统架构与边界.md`
- 引擎参考：`banana-slides/backend/README.md`
