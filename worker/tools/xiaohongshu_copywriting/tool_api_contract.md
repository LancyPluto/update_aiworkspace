# xiaohongshu_copywriting 接口契约

## 目标

- 工具编码：`xiaohongshu_copywriting`
- 工具名称：`AI 小红书文案生成器`
- 目标：根据结构化输入生成一篇可直接修改后发布的小红书文案

## 一、用户侧任务创建契约

说明：该接口由业务后端实现，这里只定义工具所需的请求和响应字段。

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
  }
}
```

### 字段说明

- `toolCode`: 固定为 `xiaohongshu_copywriting`
- `params.productName`: 产品/服务/活动名称，必填
- `params.targetCustomer`: 目标用户，必填
- `params.style`: 文案风格，必填，推荐值：`种草`、`真实分享`、`口语化`、`专业`
- `params.sellingPoints`: 核心卖点，必填
- `params.extraInfo`: 补充说明，选填，可承载价格、活动时间、门店位置、禁用词等

### 创建成功响应示例

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "status": "CREATED"
  }
}
```

## 二、用户侧任务查询契约

说明：该接口由业务后端实现，这里只定义 AI 工具返回结构。

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
    "errorCode": null,
    "errorMessage": null,
    "result": {
      "resourceType": "MARKDOWN",
      "contentText": "## 标题建议\n1. ...",
      "contentJson": {
        "titles": ["标题1", "标题2", "标题3"],
        "content": "正文内容",
        "hashtags": ["#标签1", "#标签2"],
        "cta": "行动引导"
      }
    }
  }
}
```

### 状态枚举

- `CREATED`
- `QUEUED`
- `PROCESSING`
- `SUCCESS`
- `FAILED`

## 三、Worker 内部 execution-context 契约

说明：该接口由业务后端提供给 Worker，Worker 已按此协议消费。

### 请求

- `GET /api/internal/v1/tasks/{taskId}/execution-context`

### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "toolCode": "xiaohongshu_copywriting",
    "status": "QUEUED",
    "params": {
      "productName": "五一肩颈护理套餐",
      "targetCustomer": "久坐上班族女性",
      "style": "种草",
      "sellingPoints": "价格划算、放松明显、适合节前放松",
      "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区"
    },
    "systemPrompt": "你是一个专业的小红书营销文案助手。",
    "userPromptTemplate": "请根据以下信息生成一篇适合发布在小红书的平台风格文案：\n\n- 产品/服务名称：{{productName}}\n- 目标用户：{{targetCustomer}}\n- 文案风格：{{style}}\n- 核心卖点：{{sellingPoints}}\n- 补充说明：{{extraInfo}}",
    "outputFormat": "MARKDOWN",
    "modelProviderCode": "deepseek",
    "modelName": "deepseek-chat"
  }
}
```

### Worker 依赖字段

- `toolCode`: 必须为 `xiaohongshu_copywriting`
- `params`: Prompt 渲染参数集合
- `systemPrompt`: 系统提示词
- `userPromptTemplate`: 用户提示模板，必须与 `params` 字段对齐
- `outputFormat`: 当前固定为 `MARKDOWN`
- `modelProviderCode`: 当前推荐 `deepseek`
- `modelName`: 当前推荐 `deepseek-chat`

## 四、Worker 状态回写契约

### 标记处理中

- `POST /api/internal/v1/tasks/{taskId}/processing`

请求体：

```json
{}
```

### 成功回写

- `POST /api/internal/v1/tasks/{taskId}/success`

请求体示例：

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

### 失败回写

- `POST /api/internal/v1/tasks/{taskId}/failed`

请求体示例：

```json
{
  "errorCode": "MODEL_OUTPUT_EMPTY",
  "errorMessage": "model returned empty content"
}
```

## 五、输出结构契约

### `contentText`

- 类型：`string`
- 含义：模型直接返回的 Markdown 文本

### `contentJson`

- 类型：`object`
- 含义：Worker 根据 Markdown 解析出的结构化结果

### `contentJson` 字段说明

- `titles`: 标题列表，推荐 3 条
- `content`: 正文内容
- `hashtags`: 标签列表，推荐 5 到 8 个
- `cta`: 行动引导

## 六、错误码契约

- `PROMPT_VARIABLE_MISSING`: Prompt 模板变量缺失
- `MODEL_CALL_FAILED`: 模型调用失败
- `MODEL_TIMEOUT`: 模型调用超时
- `MODEL_OUTPUT_EMPTY`: 模型返回空内容，或结果不符合工具要求导致无法解析
- `WORKER_INTERNAL_ERROR`: Worker 内部异常

## 七、二次生成扩展字段

如果要支持反馈后二次生成，建议在 `params` 外增加一层可选扩展上下文：

```json
{
  "generationMode": "REWRITE",
  "rewriteContext": {
    "lastFeedback": ["moreColloquial", "lessAdvertising"],
    "customFeedback": "不要太像活动海报文案，更像真实体验",
    "previousResult": {
      "titles": ["标题1", "标题2", "标题3"],
      "content": "上一版正文",
      "hashtags": ["#标签1"],
      "cta": "上一版 CTA"
    }
  }
}
```

### 二次生成字段说明

- `generationMode`: `INITIAL` 或 `REWRITE`
- `rewriteContext.lastFeedback`: 结构化反馈标签
- `rewriteContext.customFeedback`: 用户自由文本反馈
- `rewriteContext.previousResult`: 上一版结果，供模型局部修订时参考
