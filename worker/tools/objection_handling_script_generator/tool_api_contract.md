# objection_handling_script_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `objection_handling_script_generator`
- 工具名称：`AI 异议处理话术生成器`
- `resourceType`: `MARKDOWN`
- 目标：基于客户异议、沟通渠道、销售阶段、产品/服务价值和处理目标生成异议处理话术

## 输入参数（params）

- `objectionText`（必填）：客户异议原话
- `objectionType`（必填）：异议类型
- `communicationChannel`（必填）：沟通渠道
- `customerType`（必填）：客户类型
- `salesStage`（必填）：销售阶段
- `customerContext`（选填）：客户背景
- `productOrService`（必填）：产品/服务
- `valueProposition`（必填）：核心价值/卖点
- `handlingGoal`（必填）：处理目标
- `tone`（必填）：话术风格
- `proofPoints`（选填）：可用佐证
- `avoidWords`（选填）：避免使用词
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 异议判断`
- `## 回应策略`
- `## 推荐话术`
- `## 追问引导`
- `## 替代表达`
- `## 后续动作建议`
- `## 合规提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
