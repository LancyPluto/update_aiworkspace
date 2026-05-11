# moments_copywriting_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `moments_copywriting_generator`
- `resourceType`: `MARKDOWN`
- 目标：根据结构化输入生成可直接发布或二次编辑的朋友圈文案

## 输入参数（params）

- `topic`（必填）：文案主题
- `targetAudience`（必填）：目标人群
- `tone`（必填）：文案风格
- `scene`（必填）：发布场景
- `sellingPoints`（必填）：核心卖点
- `lengthLevel`（必填）：文案长度（短/中）
- `cta`（选填）：行动引导
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 文案正文`
- `## 表情建议`
- `## 话题标签`
- `## 行动引导`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
