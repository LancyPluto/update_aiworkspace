# customer_followup_script_generator Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `customer_followup_script_generator`
- 工具名称：`AI 客户跟进话术生成器`
- `resourceType`: `MARKDOWN`
- 目标：基于客户类型、跟进阶段、沟通渠道、客户痛点、产品/服务价值和跟进目标生成客户跟进话术

## 输入参数（params）

- `customerType`（必填）：客户类型
- `followupStage`（必填）：跟进阶段
- `communicationChannel`（必填）：沟通渠道
- `customerProfile`（选填）：客户画像
- `customerPainPoints`（必填）：客户痛点/需求
- `productOrService`（必填）：产品/服务
- `valueProposition`（必填）：核心价值/卖点
- `followupGoal`（必填）：跟进目标
- `tone`（必填）：话术风格
- `lastInteraction`（选填）：上次沟通情况
- `objectionOrConcern`（选填）：客户顾虑
- `callToAction`（选填）：行动引导
- `avoidWords`（选填）：避免使用词
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 客户情况分析`
- `## 跟进话术`
- `## 异议处理话术`
- `## 触达节奏建议`
- `## 跟进记录建议`
- `## 合规提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
