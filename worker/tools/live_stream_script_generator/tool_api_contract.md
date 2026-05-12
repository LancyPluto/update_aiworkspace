# live_stream_script_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `live_stream_script_generator`
- 工具名称：`AI 直播话术生成器`
- `resourceType`: `MARKDOWN`
- 目标：基于直播主题、平台、人群、商品/服务、卖点和直播目标生成可执行直播话术

## 输入参数（params）

- `liveTheme`（必填）：直播主题
- `targetPlatform`（必填）：目标平台
- `targetAudience`（必填）：目标人群
- `productOrService`（必填）：产品/服务
- `sellingPoints`（必填）：核心卖点
- `liveGoal`（必填）：直播目标
- `liveDuration`（必填）：直播时长
- `tone`（必填）：话术风格
- `promotionMechanism`（选填）：活动机制
- `interactionFocus`（选填）：互动重点
- `avoidWords`（选填）：避免使用词
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 开场话术`
- `## 产品讲解话术`
- `## 互动引导话术`
- `## 促单转化话术`
- `## 异议处理话术`
- `## 直播节奏安排`
- `## 合规提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
