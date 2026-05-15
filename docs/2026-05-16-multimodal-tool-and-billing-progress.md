# 2026-05-16 多模态工具与计费机制落地总结

## 今日成果

本次推进重点是把“AI 工具”从纯文本模板工具，推进到可配置多模态工具，并把模型成本计费从单一 token 口径扩展到图片、视频等按次计费场景。

### 1. 工具类型与动态表单

- 后端工具增加 `toolType`、`inputModality`、`outputModality`、`configNote`。
- 管理后台工具配置页增加工具类型、输入/输出模态和配置说明。
- 管理后台字段模板增加文生图等模板，避免所有工具都只能使用同一套文本表单。
- 用户端动态表单支持 `radio`、`checkbox`、`slider`、`image`、`file` 等字段类型。
- 用户端结果渲染按输出模态区分文本、JSON、图片、音频、视频、报告等结果。

### 2. SiliconFlow 生图接入

- 模型配置新增 `siliconflow_images` provider。
- Worker 增加图片生成路由和 `ImageGenerationHandler`，调用 SiliconFlow `/v1/images/generations`。
- 生图结果会落盘到 `GENERATED_MEDIA_DIR/images/{taskId}/image-{n}.{ext}`。
- 任务结果返回稳定 `/generated/images/{taskId}/...` 地址，同时保留 `sourceUrl` 便于排查。
- Worker 会跳过 `SUCCESS`、`FAILED`、`CANCELLED` 终态任务，避免重复消费导致非法状态流转。

### 3. 字段模板重复保存修复

- 修复后台字段模板重复保存时报 `Duplicate entry '{schemaId}-prompt'` 的问题。
- 保存字段 schema 时会先更新已存在字段，再插入新增字段，不再因为唯一键冲突失败。

### 4. 模型配置与供应商入口

- 模型配置增加 `consoleUrl`、`balanceUrl`、`docsUrl`。
- 管理后台模型卡片支持跳转供应商控制台、余额页、文档页。
- `siliconflow_images` 连通性测试不再错误调用 agent-service，而是由后端直接接受配置，运行时交给 worker 调用。

### 5. 计费单位从 K 调整为 M，并兼容按次计费

- 管理后台模型价格展示从 `/ 1K` 改为 `/ 1M`。
- 后端新增 `inputTokenPricePer1m`、`outputTokenPricePer1m`。
- 旧字段 `inputTokenPricePer1k`、`outputTokenPricePer1k` 暂时保留用于兼容老数据。
- `DataInitializer` 启动时会把旧 1K 价格自动迁移为 1M 价格。
- 模型配置新增 `billingUnit`：
  - `TOKEN_PER_M`：按百万 token 计费。
  - `PER_CALL`：按生成次数、图片张数、视频条数等计费。
- 模型配置新增 `unitPrice`，用于 `PER_CALL` 单价。
- Worker 成功回调新增 `billableUnits`。
- 生图 worker 会按实际返回图片数量上报 `billableUnits`。
- 计费日志新增 `billingUnit`、`billableUnits`、`unitPrice`、`inputTokenPricePer1m`、`outputTokenPricePer1m`。

## 接口同步

已同步 `docs/api/openapi.yml`：

- 新增后台模型配置接口：
  - `GET /api/admin/v1/agent/model-config`
  - `PUT /api/admin/v1/agent/model-config`
  - `POST /api/admin/v1/agent/model-config`
  - `GET /api/admin/v1/agent/model-config/list`
  - `PUT /api/admin/v1/agent/model-config/{id}`
  - `DELETE /api/admin/v1/agent/model-config/{id}`
  - `POST /api/admin/v1/agent/model-config/{id}/default`
  - `POST /api/admin/v1/agent/model-config/test`
- 新增后台计费接口：
  - `GET /api/admin/v1/billing/overview`
  - `GET /api/admin/v1/billing/usage-logs`
- `AgentModelConfigRequest/Response` 增加 1M token 单价、按次计费字段和供应商入口字段。
- `TaskSuccessRequest` 增加 `promptTokens`、`completionTokens`、`billableUnits`。
- `ToolSummary/UpsertToolRequest/TaskExecutionContext` 增加工具类型和输入输出模态字段。

## 数据库同步

已同步以下初始化路径，其他人拉取后可直接启动补齐：

- `sql/009_agent_model_configs.sql`
  - `console_url`
  - `balance_url`
  - `docs_url`
  - `input_token_price_per_1m`
  - `output_token_price_per_1m`
  - `billing_unit`
  - `unit_price`
- `sql/015_billing_usage_logs.sql`
  - `input_token_price_per_1m`
  - `output_token_price_per_1m`
  - `billing_unit`
  - `billable_units`
  - `unit_price`
- `backend/src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java`
  - 启动时自动补齐线上/本地旧库缺失列。
  - 自动把旧 `*_per_1k` 价格迁移为 `*_per_1m`。
- `backend/src/test/resources/schema-test.sql`
  - H2 测试库结构已同步。

## 验证

- `mvn test` 通过，103 tests。
- `mvn test -Dtest=WorkerInternalApiTest` 通过，覆盖按次计费成本记录。
- `python worker/scripts/run_fake_image_generation_test.py` 通过。
- `python -m py_compile worker/handlers/image_generation_handler.py worker/handlers/generated_image_persister.py worker/scripts/run_fake_image_generation_test.py` 通过。
- `npm run build` 管理端通过。
- `npm run build` 用户端通过。
- `git diff --check` 无错误，仅 CRLF 提示。

## 后续建议

1. 真实联调 SiliconFlow 生图，确认管理端配置、用户端动态表单、worker 生成、图片落盘、结果展示闭环。
2. 视频和数字人任务接入时复用 `billableUnits`，按生成条数或供应商实际计费单位上报。
3. 后续若接入供应商余额 API，再为不同 provider 增加余额查询适配器；当前先保留控制台/余额页跳转，避免过早做不稳定适配。
4. Agent 方向继续收敛：先恢复稳定工具调用和任务队列能力，再决定是否引入独立 agent-service 或 DeepAgents。
