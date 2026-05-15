# 小红书工具最小联调清单

> 这份文档只保留小红书工具的最小联调提示。
> Worker 与后端的统一字段契约请以 `worker/WORKER_BACKEND_FINAL_CONTRACT.md` 为准。

## 后端最少需要给到的能力

- 能创建 `xiaohongshu_copywriting` 任务
- 能把任务推到 Redis 队列 `ai:task:queue`
- 能提供 Worker 所需的 `execution-context`
- 能接收 Worker 的 `processing / success / failed` 回写
- 能查询任务结果并返回给前端

## 一、创建任务时必须传的字段

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

## 二、Worker 拉取上下文时必须返回的字段

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "toolCode": "xiaohongshu_copywriting",
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

## 三、Worker 成功回写时你们后端要接收的字段

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

## 四、Worker 失败回写时你们后端要接收的字段

```json
{
  "errorCode": "MODEL_OUTPUT_EMPTY",
  "errorMessage": "model returned empty content"
}
```

## 五、这几个错误码要认

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`

## 六、数据库最少要准备什么

- 执行 [001_init_v1.sql](file:///Users/a1-6/Project/ai-tool-market/sql/001_init_v1.sql)
- 执行 [002_seed_xiaohongshu_copywriting.sql](file:///Users/a1-6/Project/ai-tool-market/sql/002_seed_xiaohongshu_copywriting.sql)

## 七、联调时最容易出错的点

- `toolCode` 不是 `xiaohongshu_copywriting`
- `userPromptTemplate` 变量名和 `params` 对不上
- `outputFormat` 不是 `MARKDOWN`
- `contentJson` 没有落库或没有透出查询接口
- Worker 回写接口没有校验内部 token

## 八、后端联调完成的验收标准

- 能创建任务
- 能查到任务进入 `QUEUED / PROCESSING / SUCCESS`
- 成功时能拿到 `contentText`
- 成功时能拿到 `contentJson.titles / content / hashtags / cta`
- 失败时能拿到 `errorCode / errorMessage`
