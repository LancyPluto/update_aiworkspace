# short_video_script_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `short_video_script_generator`
- 工具名称：`AI 短视频脚本生成器`
- `resourceType`: `MARKDOWN`
- 目标：基于视频主题、平台、人群、卖点与转化目标生成可拍摄的短视频脚本

## 输入参数（params）

- `videoTopic`（必填）：视频主题
- `targetPlatform`（必填）：目标平台
- `targetAudience`（必填）：目标人群
- `promotionObject`（必填）：推广对象
- `coreSellingPoints`（必填）：核心卖点
- `scriptStyle`（必填）：脚本风格
- `videoLength`（必填）：视频时长
- `shootingScenario`（选填）：拍摄场景
- `callToAction`（选填）：行动引导
- `keywords`（选填）：希望包含关键词
- `avoidWords`（选填）：避免使用词
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 开场钩子`
- `## 分镜脚本`
- `## 口播文案`
- `## 拍摄与剪辑建议`
- `## 标题与标签建议`
- `## 合规提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
