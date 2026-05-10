# xiaohongshu_copywriting 后端接口契约

## 目标

- 工具编码：`xiaohongshu_copywriting`
- 工具名称：`AI 小红书文案生成器`
- 目标：根据结构化输入生成一篇可直接修改后发布的小红书文案
- 标准来源：以后端 `docs/api/openapi.yml` 当前已实现接口为准

## 一、用户侧工具信息接口

### 获取已上线工具列表

- `GET /api/v1/tools`
- 鉴权：公开接口
- 用途：前端展示工具列表，响应体中 `data.list` 包含已上线工具。

### 获取工具详情和动态表单字段

- `GET /api/v1/tools/{toolCode}`
- 鉴权：公开接口
- `toolCode`: `xiaohongshu_copywriting`
- 用途：前端获取工具基础信息和 `fields` 动态表单配置。

## 二、用户侧创建 AI 任务

- `POST /api/v1/tasks`
- 鉴权：`Authorization: Bearer <accessToken>`
- 用途：用户提交小红书文案生成参数，创建任务并进入后端任务队列状态。

### 请求示例

```json
{
  "toolCode": "xiaohongshu_copywriting",
  "params": {
    "productName": "五一肩颈护理套餐",
    "targetCustomer": "久坐上班族女性",
    "style": "种草",
    "sellingPoints": "价格划算、放松明显、适合节前放松",
    "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区"
  },
  "clientRequestId": "uuid-from-frontend"
}
```

### 字段说明

- `toolCode`: 固定为 `xiaohongshu_copywriting`
- `params.productName`: 产品/服务/活动名称，必填
- `params.targetCustomer`: 目标用户，必填
- `params.style`: 文案风格，必填，推荐值：`种草`、`真实分享`、`口语化`、`专业`
- `params.sellingPoints`: 核心卖点，必填
- `params.extraInfo`: 补充说明，选填，可承载价格、活动时间、门店位置、禁用词等
- `clientRequestId`: 选填，前端生成的幂等请求标识

### 创建成功响应示例

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "status": "QUEUED",
    "progress": 0,
    "progressMessage": "任务已排队"
  }
}
```

## 三、用户侧任务查询

### 查询任务状态

- `GET /api/v1/tasks/{taskId}/status`
- 鉴权：`Authorization: Bearer <accessToken>`

### 查询任务详情和生成结果

- `GET /api/v1/tasks/{taskId}`
- 鉴权：`Authorization: Bearer <accessToken>`

### 成功响应示例

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "toolCode": "xiaohongshu_copywriting",
    "status": "SUCCESS",
    "progress": 100,
    "progressMessage": "生成完成",
    "params": {
      "productName": "五一肩颈护理套餐",
      "targetCustomer": "久坐上班族女性",
      "style": "种草",
      "sellingPoints": "价格划算、放松明显、适合节前放松",
      "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区"
    },
    "result": {
      "resourceType": "MARKDOWN",
      "contentText": "## 标题建议\n1. ...\n\n## 正文\n...\n\n## 标签建议\n#...\n\n## 行动引导\n..."
    }
  }
}
```

### 状态枚举

- `CREATED`
- `QUEUED`
- `PROCESSING`
- `RETRYING`
- `SUCCESS`
- `FAILED`
- `TIMEOUT`
- `CANCELLED`

## 四、Worker 内部 execution-context

- `GET /api/internal/v1/tasks/{taskId}/execution-context`
- 鉴权：`X-Internal-Token: <INTERNAL_API_TOKEN>`
- 用途：Worker 获取任务执行上下文。
- 注意：以后端当前 `openapi.yml` 为准，该接口只返回任务、用户参数和字段配置；不返回 `systemPrompt`、`userPromptTemplate`、`outputFormat`、`modelProviderCode`、`modelName`。

### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "userId": 3,
    "toolId": 1,
    "toolCode": "xiaohongshu_copywriting",
    "toolName": "AI 小红书文案生成器",
    "status": "QUEUED",
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
        "fieldName": "产品/服务名称",
        "fieldType": "TEXT",
        "placeholder": "例如：五一肩颈护理套餐",
        "options": null,
        "required": true,
        "sortOrder": 1
      }
    ]
  }
}
```

### Worker 使用规则

- `toolCode`: 必须为 `xiaohongshu_copywriting`
- `params`: 小红书 prompt 构建的输入来源
- `fields`: 字段配置，可用于校验或调试，不作为 prompt 必需输入
- Prompt 模板、默认 system prompt、输出格式和模型配置由 Worker 本地工具实现及环境变量负责

## 五、Worker 状态回写

### 标记处理中

- `POST /api/internal/v1/tasks/{taskId}/processing`
- 鉴权：`X-Internal-Token: <INTERNAL_API_TOKEN>`

请求体：

```json
{
  "progress": 35,
  "progressMessage": "AI 正在生成内容"
}
```

### 成功回写

- `POST /api/internal/v1/tasks/{taskId}/success`
- 鉴权：`X-Internal-Token: <INTERNAL_API_TOKEN>`

请求体示例：

```json
{
  "resourceType": "MARKDOWN",
  "contentText": "## 标题建议\n1. ...\n\n## 正文\n...\n\n## 标签建议\n#...\n\n## 行动引导\n..."
}
```

后端当前 `WorkerSuccessRequest` 只接收并落库 `resourceType` 和 `contentText`。

### 失败回写

- `POST /api/internal/v1/tasks/{taskId}/failed`
- 鉴权：`X-Internal-Token: <INTERNAL_API_TOKEN>`

请求体示例：

```json
{
  "errorCode": "MODEL_OUTPUT_EMPTY",
  "errorMessage": "model returned empty content"
}
```

## 六、小红书输出文本契约

### `contentText`

- 类型：`string`
- 含义：模型直接返回的 Markdown 文本
- 后端当前只保存该字段，不保存结构化 `contentJson`

### Markdown 必须包含的段落

- `## 标题建议`
- `## 正文`
- `## 标签建议`
- `## 行动引导`

Worker 会在回写前解析并校验这些段落；解析失败会回写失败状态。

## 七、错误码契约

- `PROMPT_VARIABLE_MISSING`: Prompt 模板变量缺失
- `MODEL_CALL_FAILED`: 模型调用失败
- `MODEL_TIMEOUT`: 模型调用超时
- `MODEL_OUTPUT_EMPTY`: 模型返回空内容，或结果不符合工具要求导致无法解析
- `WORKER_INTERNAL_ERROR`: Worker 内部异常

## 八、二次生成扩展字段

后端当前 `CreateTaskRequest.params` 是开放 object。如果要支持反馈后二次生成，建议先以后端接口评审为准，再约定 `params` 内或额外上下文的承载方式。

```json
{
  "toolCode": "xiaohongshu_copywriting",
  "params": {
    "productName": "五一肩颈护理套餐",
    "targetCustomer": "久坐上班族女性",
    "style": "种草",
    "sellingPoints": "价格划算、放松明显、适合节前放松",
    "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区",
    "generationMode": "REWRITE",
    "lastFeedback": ["moreColloquial", "lessAdvertising"],
    "customFeedback": "不要太像活动海报文案，更像真实体验"
  }
}
```

当前 Worker 仍支持内部 `generationMode/rewriteContext` 扩展上下文；但后端 `openapi.yml` 尚未把二次生成定义为正式接口字段。

## 九、端到端调用顺序

1. 用户登录，拿到 `accessToken`
2. 前端调用 `GET /api/v1/tools/xiaohongshu_copywriting` 获取表单字段
3. 前端调用 `POST /api/v1/tasks` 创建任务
4. 后端任务进入 `QUEUED`
5. Worker 从 Redis 队列消费任务消息
6. Worker 调用 `GET /api/internal/v1/tasks/{taskId}/execution-context`
7. Worker 调用 `POST /api/internal/v1/tasks/{taskId}/processing`
8. Worker 根据 `params` 构建 prompt 并调用模型
9. Worker 调用 `POST /api/internal/v1/tasks/{taskId}/success`
10. 用户调用 `GET /api/v1/tasks/{taskId}` 查看 `result.contentText`
