# store_campaign_planner Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `store_campaign_planner`
- 工具名称：`AI 门店活动策划器`
- `resourceType`: `MARKDOWN`
- 目标：基于门店类型、活动目标、周期、预算、客群和主推商品/服务生成线下门店活动方案

## 输入参数（params）

- `storeType`（必填）：门店类型
- `storeNameOrPositioning`（必填）：门店名称/定位
- `campaignGoal`（必填）：活动目标
- `campaignTheme`（必填）：活动主题
- `campaignPeriod`（必填）：活动周期
- `targetCustomers`（必填）：目标客群
- `productsOrServices`（必填）：主推商品/服务
- `budgetRange`（必填）：预算范围
- `availableResources`（选填）：可用资源
- `promotionMechanism`（选填）：活动机制
- `channels`（选填）：触达渠道
- `constraints`（选填）：限制条件
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 活动总览`
- `## 客群策略`
- `## 活动玩法设计`
- `## 执行排期`
- `## 物料与话术建议`
- `## 人员分工`
- `## 复盘指标`
- `## 风险提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
