<<<<<<< HEAD

# ai-tool-market

# 03. 成员 3：AI 任务 Worker

&#x20;

## 角色定位

你负责 Redis 队列消费、Prompt 拼装、AI 模型调用、任务结果回写。不要直接绕过后端改核心业务表，算力和任务状态由后端统一处理。

## 必须交付

```
Redis 队列消费者
任务执行上下文获取
Prompt 模板变量替换
AI 模型调用
成功结果回写
失败状态回写
Worker 启动说明

```

## Redis 队列

队列名：

```
ai:task:queue

```

消息格式：

```
{
  "taskId": 90001,
  "taskNo": "T202605070001",
  "toolCode": "xiaohongshu_copywriting",
  "traceId": "request-id",
  "createdAt": "2026-05-07T10:00:00"
}

```

## Worker 执行流程

```
1. 从 Redis 获取消息
2. 调用 GET /api/internal/v1/tasks/{taskId}/execution-context
3. 调用 POST /api/internal/v1/tasks/{taskId}/processing
4. 根据 field inputs + prompt template 拼装 Prompt
5. 调用 AI 模型
6. 成功则调用 POST /api/internal/v1/tasks/{taskId}/success
7. 失败则调用 POST /api/internal/v1/tasks/{taskId}/failed

```

## 执行上下文返回结构

后端返回：

```
{
  "taskId": 90001,
  "taskNo": "T202605070001",
  "toolCode": "xiaohongshu_copywriting",
  "status": "QUEUED",
  "params": {
    "productName": "五一护理套餐",
    "targetCustomer": "年轻女性",
    "style": "种草"
  },
  "systemPrompt": "你是一个专业小红书文案助手。",
  "userPromptTemplate": "请根据 {{productName}} 为 {{targetCustomer}} 生成一篇 {{style}} 风格文案。",
  "outputFormat": "MARKDOWN",
  "modelProviderCode": "deepseek",
  "modelName": "deepseek-chat"
}

```

## Prompt 变量替换规则

```
{{productName}} 替换为 params.productName
{{targetCustomer}} 替换为 params.targetCustomer
{{style}} 替换为 params.style
缺少变量时，回写 FAILED，errorCode = PROMPT_VARIABLE_MISSING

```

## 成功回写

```
POST /api/internal/v1/tasks/{taskId}/success

```

```
{
  "resourceType": "MARKDOWN",
  "contentText": "生成结果",
  "contentJson": null,
  "modelProviderCode": "deepseek",
  "modelName": "deepseek-chat"
}

```

## 失败回写

```
POST /api/internal/v1/tasks/{taskId}/failed

```

```
{
  "errorCode": "MODEL_CALL_FAILED",
  "errorMessage": "模型调用失败"
}

```

## V1 错误码

错误码

场景

`PROMPT_VARIABLE_MISSING`

Prompt 变量缺失

`MODEL_CALL_FAILED`

模型调用失败

`MODEL_TIMEOUT`

模型超时

`MODEL_OUTPUT_EMPTY`

模型返回空内容

`WORKER_INTERNAL_ERROR`

Worker 内部异常

## 每日交付

日期

交付

第 1 天

Worker 项目启动、Redis 连接、消费空消息

第 2 天

execution-context 接口联调

第 3 天

任务状态 PROCESSING 回写

第 4 天

AI 调用和 SUCCESS/FAILED 回写

第 5 天

异常处理、超时处理、日志

第 6 天

联调修 Bug

第 7 天

Worker 启动文档和演示环境验证

## 不做

```
多模型路由
文件上传
图片/视频生成
直接修改 credit_accounts
直接修改 ai_tasks 成功状态
复杂死信队列后台

```


