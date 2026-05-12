# short_video_topic_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `short_video_topic_generator`
- 工具名称：`AI 短视频选题生成器`
- `resourceType`: `MARKDOWN`
- 目标：基于账号定位、平台、人群、内容目标和产品/业务信息生成可执行短视频选题

## 输入参数（params）

- `accountPositioning`（必填）：账号/品牌定位
- `targetPlatform`（必填）：目标平台
- `targetAudience`（必填）：目标人群
- `contentGoal`（必填）：内容目标
- `topicDirection`（必填）：选题方向
- `productOrService`（选填）：产品/服务信息
- `topicCount`（必填）：选题数量
- `stylePreference`（必填）：内容风格
- `avoidTopics`（选填）：避免方向
- `keywords`（选填）：希望包含关键词
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 选题清单`
- `## 推荐优先级`
- `## 创作角度`
- `## 标题与标签建议`
- `## 执行建议`
- `## 风险提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
