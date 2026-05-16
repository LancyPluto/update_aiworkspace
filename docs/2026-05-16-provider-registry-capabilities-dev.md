# 2026-05-16 开发记录：模型 Provider 目录、capabilities 与多模态对齐

本文档描述当日落地的「注册表驱动的模型 Provider 定义」「管理端 Provider 目录 API」「`agent_model_configs` 能力字段」「任务/工具/Worker 侧一致性校验」及相关 OpenAPI、前端与 Worker 改动，便于后续接入新供应商或复核架构。

## 1. 目标

- 用 **YAML 注册表** 替代后端硬编码的 provider 白名单，统一声明协议代码、展示名、默认可选 URL/模型、计费默认值、`testStrategy`、`workerReady`、支持的 **execution capabilities**。
- 管理端通过 **HTTP 目录 API** 拉取可选 Provider，模型配置表单支持 **capabilities 多选**；工具页按 **`executionHandler` / `toolType`** 过滤可选模型配置。
- 持久化层为每条模型凭证增加 **`capabilities` JSON**，保存时与注册表及工具 execution 语义对齐；任务创建与 Worker 拉取 execution-context 前做校验。
- Worker 侧维护 **第二份 registry**（Python），handler 在运行时校验 capability / worker_ready，与后端契约一致。

## 2. 后端

### 2.1 Provider 注册表

- 资源文件：[`backend/src/main/resources/model-providers.yml`](backend/src/main/resources/model-providers.yml)
- 启动加载：`ModelProviderRegistry`（[`backend/src/main/java/com/aiminilab/aitoolmarket/agent/config/ModelProviderRegistry.java`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/config/ModelProviderRegistry.java)）
- 每条定义对应 `ModelProviderDefinition`（同包 `ModelProviderDefinition.java`）。

说明：`mock` 等条目可按现有集成测试需求配置（例如连通性仍走 agent-service）；多模态相关 provider 需写明 `capabilities`，并与 Worker registry 同步。

### 2.2 能力解析与模型配置服务

- [`ModelCapabilityService`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/ModelCapabilityService.java)：按注册表校验 capability 集合、与 execution 场景匹配等。
- [`ModelCapabilitiesCodec`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/support/ModelCapabilitiesCodec.java)：JSON 与列表互转。
- [`AgentModelConfigServiceImpl`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentModelConfigServiceImpl.java)：
  - provider 合法性以注册表为准；
  - **capabilities**：请求未携带且 provider 未变更时保留库内存量；否则与注册表合并/校验；
  - 连通性测试按 `testStrategy` 分流（agent-service / 直连接受 / worker 侧等）。

### 2.3 管理端 Provider 目录 API

- 控制器：[`AdminModelProviderController`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AdminModelProviderController.java)
  - `GET /api/admin/v1/model-providers`（可选 query：`capability`）
  - `GET /api/admin/v1/model-providers/{code}`
- DTO：[`ModelProviderResponse`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/ModelProviderResponse.java)

### 2.4 数据库与 Mapper

- 迁移脚本：[`sql/018_agent_model_config_capabilities.sql`](sql/018_agent_model_config_capabilities.sql)（列 `agent_model_configs.capabilities`）。
- 测试库结构：[`backend/src/test/resources/schema-test.sql`](backend/src/test/resources/schema-test.sql)
- [`AgentModelConfigMapper`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentModelConfigMapper.java)：insert/update 包含 `capabilities`。

### 2.5 任务与工具校验

- [`TaskServiceImpl`](backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java)：创建任务前校验模型 binding 与 capability。
- [`InternalTaskServiceImpl`](backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java)：下发 execution-context 前再次校验。
- [`ExecutionModelConfigResponse`](backend/src/main/java/com/aiminilab/aitoolmarket/task/dto/ExecutionModelConfigResponse.java)：`capabilities` 列表随上下文返回 Worker。
- [`ToolServiceImpl`](backend/src/main/java/com/aiminilab/aitoolmarket/tool/service/impl/ToolServiceImpl.java)：**创建工具**时在模板/默认字段应用完成、持久化之后再调用 `validateToolModelBinding`；**apply-template** 结束后校验，避免 `execution_handler` 尚未写入导致误判。
- 工具摘要/详情 DTO：`ToolSummaryResponse`、`ToolDetailResponse` 增加 `executionHandler`（供管理端过滤模型）。

### 2.6 其它

- [`AgentRunServiceImpl`](backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java) 构造 `AgentModelConfigRequest` 时需与 record 构造函数参数对齐（含 `isDefault`、`capabilities`）。

### 2.7 测试

- [`ModelProviderRegistryTest`](backend/src/test/java/com/aiminilab/aitoolmarket/agent/config/ModelProviderRegistryTest.java)
- [`WorkerInternalApiTest`](backend/src/test/java/com/aiminilab/aitoolmarket/task/WorkerInternalApiTest.java)：按次计费等场景注意默认模型与 `IMAGE_GENERATION` 工具类型；用例结束恢复通用 provider，避免污染后续测试。
- [`OpenApiContractTest`](backend/src/test/java/com/aiminilab/aitoolmarket/common/OpenApiContractTest.java)：断言 OpenAPI 中包含 `/api/admin/v1/model-providers` 等路径及 `ModelProviderDescriptor` 等 schema 片段。

## 3. OpenAPI

- 文件：[`docs/api/openapi.yml`](docs/api/openapi.yml)
- 路径：`/api/admin/v1/model-providers`、`/api/admin/v1/model-providers/{code}`
- Schema：`ModelProviderDescriptor`、`ModelProviderApiResponse`、`ModelProviderListApiResponse`
- `AgentModelConfig` / `AgentModelConfigRequest`：`provider` 为广义 `string`（不再固定 enum）；增加 `capabilities` 数组
- `ToolSummary`：`executionHandler`
- `ExecutionModelConfig`（任务执行上下文内嵌模型）：`capabilities`

## 4. 管理端前端

- API：`admin-frontend/lib/api/model-providers.ts`、`agent-model.ts`（保存/测试模型配置时携带 `capabilities`）
- 类型：`admin-frontend/lib/api/types.ts`（`ModelProviderDescriptor`、`ToolSummary.executionHandler`、provider 类型放宽等）
- 模型设置页：`admin-frontend/components/admin/agent-model-settings.tsx`（目录驱动 Provider、能力多选）
- 工具列表：`admin-frontend/app/tools/page.tsx`（按 executionHandler / toolType 过滤可选模型）

## 5. Worker

- Registry：[`worker/providers/registry.py`](worker/providers/registry.py)、[`worker/providers/__init__.py`](worker/providers/__init__.py)
- Handlers（示例）：[`worker/handlers/image_generation_handler.py`](worker/handlers/image_generation_handler.py)、[`video_generation_handler.py`](worker/handlers/video_generation_handler.py)、[`digital_human_video_handler.py`](worker/handlers/digital_human_video_handler.py) 内增加基于 registry 的 capability / `worker_ready` 校验。

## 6. 新 Provider 接入清单（双注册表）

新增或调整供应商协议时建议按顺序完成：

1. **后端** [`model-providers.yml`](backend/src/main/resources/model-providers.yml)：补 `code`、`label`、`capabilities`、`testStrategy`、`workerReady`、默认 URL/模型/计费提示等。
2. **Worker** [`worker/providers/registry.py`](worker/providers/registry.py)：同步相同 `code`、能力与就绪标记，避免运行时歧义。
3. **Java**：若存在特殊测试路由或计费分支，在 `AgentModelConfigServiceImpl` / 相关枚举中跟进（优先仍走注册表字段驱动）。
4. **数据库**：无需重复加列；新环境执行 [`sql/018_agent_model_config_capabilities.sql`](sql/018_agent_model_config_capabilities.sql)（若尚未 applied）。
5. **OpenAPI**：若响应字段有扩展，更新 [`docs/api/openapi.yml`](docs/api/openapi.yml) 与契约测试。
6. **回归**：`mvn test`（backend）、管理端 `npm run build`、关键 Worker handler 的 `py_compile` 或集成脚本。

## 7. 与既有进度文档的关系

- 多模态工具形态、按次计费、SiliconFlow 生图等概述仍见：[`docs/2026-05-16-multimodal-tool-and-billing-progress.md`](docs/2026-05-16-multimodal-tool-and-billing-progress.md)
- 本文专注 **Provider 目录化 + capabilities + 双 registry 同步**；两处可交叉引用。
