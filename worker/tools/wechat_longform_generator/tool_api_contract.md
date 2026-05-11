# wechat_longform_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `wechat_longform_generator`
- `resourceType`: `MARKDOWN`
- 目标：根据结构化输入生成公众号长文草稿

## 输入参数（params）

- `topic`（必填）：文章主题
- `audience`（必填）：目标读者
- `goal`（必填）：写作目标
- `tone`（必填）：语气风格
- `lengthLevel`（必填）：篇幅档位（短/中/长）
- `keyPoints`（必填）：核心要点
- `cta`（选填）：行动引导
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `# 标题`
- `## 导语`
- `## 正文`
  - 正文中至少一个 `###` 子标题
- `## 总结`
- `## 行动引导`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
