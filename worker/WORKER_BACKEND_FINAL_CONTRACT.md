# Worker-Backend Final Contract

## 目的

这份文档是 AI 工具 Worker 与后端 internal API 的单一事实来源。

目标是统一以下四处的字段契约，避免后续联调建立在漂移接口上：

- Worker 运行时代码
- 后端 internal task API
- 联调脚本
- 工具接入文档

本文档定义的是 `V1` 阶段建议采用的最终协议，不等于当前代码已经全部实现。

## 设计原则

1. `execution-context` 是 Worker 的唯一事实来源，Worker 不应再从工具文档或脚本猜字段。
2. `ai_tasks` 必须保存本次任务实际使用的 `prompt_version_id`，保证结果可追溯。
3. `success` 回写以“可展示文本 + 可选结构化结果 + 实际模型信息”为标准格式。
4. 非必须的本地默认值只允许用于开发兜底，不应作为正式联调依赖。

## 当前差异

当前仓库里有两类明显漂移：

1. 后端 `ExecutionContextResponse` 只返回基础任务字段与 `params`、`fields`，但 Worker 和联调脚本已经依赖 `systemPrompt`、`userPromptTemplate`、`generationMode`、`rewriteContext`、`modelName`。
2. Worker `mark_success` 只稳定回写 `resourceType` 和 `contentText`，但工具文档和脚本已经假设存在 `contentJson`、`modelProviderCode`、`modelName`。

因此，后续实现应以本文档为准，而不是以某一边的当前代码为准。

## 一、任务队列消息

生产队列事实源为 RabbitMQ；Redis 队列仅保留本地开发或历史兼容。RabbitMQ 默认队列名：

```text
ai.tool.normal
```

Redis 兼容队列名为 `ai:task:queue`。

消息体：

| 字段 | 类型 | 必填 | 来源 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | `number` | 是 | 后端 `ai_tasks.id` | Worker 唯一消费主键 |
| `taskNo` | `string` | 是 | 后端 `ai_tasks.task_no` | 用于日志和排障 |
| `toolCode` | `string` | 是 | 后端 `ai_tools.tool_code` | 用于路由具体工具 |
| `traceId` | `string` | 否 | 后端生成 | 用于链路追踪 |
| `createdAt` | `string` | 否 | 后端生成 | ISO-8601 时间 |

示例：

```json
{
  "taskId": 90001,
  "taskNo": "T202605070001",
  "toolCode": "xiaohongshu_copywriting",
  "traceId": "request-id",
  "createdAt": "2026-05-07T10:00:00"
}
```

## 二、获取执行上下文

接口：

```text
GET /api/internal/v1/tasks/{taskId}/execution-context
```

统一响应包装：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {}
}
```

### `data` 字段最终协议

| 字段 | 类型 | 必填 | 来源 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | `number` | 是 | `ai_tasks.id` | 任务主键 |
| `taskNo` | `string` | 是 | `ai_tasks.task_no` | 任务编号 |
| `userId` | `number` | 是 | `ai_tasks.user_id` | 任务所属用户 |
| `toolId` | `number` | 是 | `ai_tasks.tool_id` | 工具主键 |
| `toolCode` | `string` | 是 | `ai_tools.tool_code` | Worker 工具路由键 |
| `toolName` | `string` | 是 | `ai_tools.tool_name` | 便于日志和审计 |
| `status` | `string` | 是 | `ai_tasks.status` | Worker 拉取时通常为 `QUEUED` |
| `promptVersionId` | `number` | 是 | `ai_tasks.prompt_version_id` | 本次任务锁定的 Prompt 版本 |
| `params` | `object` | 是 | `ai_tasks.params_json` | 任务输入参数 |
| `fields` | `array` | 是 | `tool_field_items` | 输入字段元数据，便于审计与排障 |
| `systemPrompt` | `string` | 条件必填 | `tool_prompt_versions.system_prompt` | 走通用提示词链路时必填；工具专用 builder 可空 |
| `userPromptTemplate` | `string` | 条件必填 | `tool_prompt_versions.user_prompt_template` | 走通用模板渲染链路时必填；工具专用 builder 可空 |
| `outputFormat` | `string` | 是 | `tool_prompt_versions.output_format` | 默认 `MARKDOWN` |
| `generationMode` | `string` | 否 | `params` 或任务扩展字段 | 默认 `INITIAL`，取值如 `INITIAL`、`REWRITE` |
| `rewriteContext` | `object` | 条件必填 | `params` 或任务扩展字段 | 当 `generationMode=REWRITE` 时必填 |
| `modelProviderCode` | `string` | 是 | 后端运行时模型配置 | 本次任务实际使用的模型供应商 |
| `modelName` | `string` | 是 | 后端运行时模型配置 | 本次任务实际使用的模型名称 |

### `fields` 子项建议结构

`fields` 用于补充任务输入 schema，不是 Worker 主逻辑的唯一输入来源，但建议完整返回：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fieldKey` | `string` | 是 | 参数键 |
| `fieldName` | `string` | 是 | 展示名称 |
| `fieldType` | `string` | 是 | 如 `TEXT`、`TEXTAREA`、`SELECT` |
| `required` | `boolean` | 是 | 是否必填 |
| `options` | `array` | 否 | 选项类字段 |
| `placeholder` | `string` | 否 | 占位提示 |
| `helpText` | `string` | 否 | 补充说明 |
| `sortOrder` | `number` | 否 | 字段顺序 |

### 示例

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "userId": 10001,
    "toolId": 12,
    "toolCode": "xiaohongshu_copywriting",
    "toolName": "AI 小红书文案生成器",
    "status": "QUEUED",
    "promptVersionId": 31,
    "params": {
      "productName": "五一肩颈护理套餐",
      "targetCustomer": "久坐上班族女性",
      "style": "种草",
      "sellingPoints": "价格划算、放松明显、适合节前放松",
      "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区"
    },
    "fields": [
      {
        "fieldKey": "productName",
        "fieldName": "产品名称",
        "fieldType": "TEXT",
        "required": true,
        "options": []
      }
    ],
    "systemPrompt": "你是一个专业的小红书营销文案助手。",
    "userPromptTemplate": "请根据以下信息生成一篇适合发布在小红书的平台风格文案：{{productName}}",
    "outputFormat": "MARKDOWN",
    "generationMode": "REWRITE",
    "rewriteContext": {
      "lastFeedback": ["moreColloquial", "lessAdvertising"],
      "customFeedback": "不要太像活动宣传，更像朋友真实分享",
      "previousResult": {
        "titles": ["标题A", "标题B", "标题C"],
        "content": "上一版正文",
        "hashtags": ["#肩颈护理", "#门店种草"],
        "cta": "上一版行动引导"
      }
    },
    "modelProviderCode": "deepseek",
    "modelName": "deepseek-chat"
  }
}
```

## 三、标记任务处理中

接口：

```text
POST /api/internal/v1/tasks/{taskId}/processing
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `progress` | `number` | 否 | 0-99，默认由后端兜底为 10 |
| `progressMessage` | `string` | 否 | 如“AI 正在生成结果” |

示例：

```json
{
  "progress": 10,
  "progressMessage": "AI is processing"
}
```

## 四、成功回写

接口：

```text
POST /api/internal/v1/tasks/{taskId}/success
```

### 最终请求体

| 字段 | 类型 | 必填 | 来源 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceType` | `string` | 是 | Worker 生成结果 | 默认 `MARKDOWN` |
| `contentText` | `string` | 是 | Worker 生成结果 | 前端直出文本 |
| `contentJson` | `object` | 否 | Worker 结果解析器 | 可选结构化结果，便于二次渲染和复用 |
| `modelProviderCode` | `string` | 是 | Worker 实际调用结果 | 最终实际使用的模型供应商 |
| `modelName` | `string` | 是 | Worker 实际调用结果 | 最终实际使用的模型名 |

### 处理规则

1. 后端至少必须保存 `resourceType`、`contentText`。
2. 后端应落库 `contentJson` 到 `ai_result_resources.content_json`。
3. 后端应记录 `modelProviderCode`、`modelName` 到任务日志或任务扩展字段，便于审计。
4. 若工具暂无结构化解析器，可不传 `contentJson`，但不得影响成功回写。

### 示例

```json
{
  "resourceType": "MARKDOWN",
  "contentText": "## 标题建议\n1. ...",
  "contentJson": {
    "titles": ["标题1", "标题2", "标题3"],
    "content": "正文内容",
    "hashtags": ["#标签1", "#标签2"],
    "cta": "行动引导"
  },
  "modelProviderCode": "deepseek",
  "modelName": "deepseek-chat"
}
```

## 五、失败回写

接口：

```text
POST /api/internal/v1/tasks/{taskId}/failed
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `errorCode` | `string` | 是 | 标准错误码 |
| `errorMessage` | `string` | 是 | 可直接排障的人类可读信息 |

示例：

```json
{
  "errorCode": "MODEL_CALL_FAILED",
  "errorMessage": "model request failed: status=500"
}
```

## 六、标准错误码

| 错误码 | 说明 | 由谁产生 |
| --- | --- | --- |
| `PROMPT_VARIABLE_MISSING` | Prompt 模板变量缺失 | Worker |
| `MODEL_CALL_FAILED` | 模型调用失败 | Worker |
| `MODEL_TIMEOUT` | 模型请求超时 | Worker |
| `MODEL_OUTPUT_EMPTY` | 模型返回空内容或无法构造成有效结果 | Worker |
| `WORKER_INTERNAL_ERROR` | Worker 内部未分类异常 | Worker |
| `BACKEND_CONTEXT_INVALID` | 后端返回的 `execution-context` 缺关键字段 | Worker |

## 七、字段来源约定

| 字段分类 | 最终来源 |
| --- | --- |
| 任务身份字段 | `ai_tasks` |
| 工具基础信息 | `ai_tools` |
| Prompt 相关字段 | `ai_tasks.prompt_version_id` 指向的 `tool_prompt_versions` |
| 输入参数 | `ai_tasks.params_json` |
| 重写上下文 | `params` 扩展块或专门任务扩展字段 |
| 模型信息 | 后端运行时模型配置，Worker 可回写最终实际值 |
| 结果文本/结构化结果 | Worker 生成并回写 |

## 八、落地顺序建议

1. 后端先把 `ai_tasks.prompt_version_id` 在创建任务时写入，并让 `execution-context` 返回 Prompt 与模型相关字段。
2. Worker 再移除对“本地默认字段”的隐式依赖，改为严格消费 `execution-context`。
3. `success` 回写扩展为 `contentJson`、`modelProviderCode`、`modelName`，并同步更新后端 DTO 与落库逻辑。
4. 所有联调脚本和工具文档只引用本文档，不再各自定义一份字段说明。

## 九、和当前代码的关系

下面这些文件目前与本文档存在差异，后续应逐步对齐：

- `backend/src/main/java/com/aiminilab/aitoolmarket/task/dto/ExecutionContextResponse.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/dto/WorkerSuccessRequest.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java`
- `worker/handlers/text_task_handler.py`
- `worker/tools/result_builder.py`
- `worker/scripts/run_fake_integration_test.py`
- `worker/scripts/run_fake_worker_redis_test.py`
- `worker/tools/xiaohongshu_copywriting/backend_integration_minimum.md`
