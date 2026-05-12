# product_detail_page_copywriter Worker 工具契约（V1）

## 工具信息

- `toolCode`: `product_detail_page_copywriter`
- 工具名称：`AI 商品详情页文案生成器`
- `resourceType`: `MARKDOWN`
- 目标：基于商品信息与表达偏好生成详情页卖点、主文案、分段排版与关键词建议，并给出合规提示

## 输入参数（params）

- `productName`（必填）
- `category`（必填）
- `targetPlatform`（必填）
- `targetCustomer`（必填）
- `sellingPoints`（必填）
- `specsOrAttributes`（选填）
- `tone`（必填）
- `lengthPreference`（必填）
- `keywords`（选填）
- `avoidWords`（选填）
- `extraInfo`（选填）

## 输出最小结构

- `## 卖点提炼`
- `## 详情页主文案`
- `## 分段排版建议`
- `## 关键词建议`
- `## 合规与发布提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
