# ecommerce_campaign_planner Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `ecommerce_campaign_planner`
- 工具名称：`AI 电商活动方案生成器`
- `resourceType`: `MARKDOWN`
- 目标：根据活动目标、周期、预算、货品和渠道信息生成可执行活动方案

## 输入参数（params）

- `campaignGoal`（必填）：活动目标
- `campaignName`（必填）：活动主题
- `targetPlatform`（必填）：目标平台
- `campaignPeriod`（必填）：活动周期
- `targetAudience`（必填）：目标人群
- `productScope`（必填）：活动货品范围
- `budgetRange`（选填）：预算范围
- `discountPolicy`（选填）：优惠机制
- `channelResources`（选填）：资源位/渠道
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 活动目标与策略概览`
- `## 活动节奏与阶段安排`
- `## 玩法设计与资源分配`
- `## 投放与转化建议`
- `## 核心指标与复盘建议`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
