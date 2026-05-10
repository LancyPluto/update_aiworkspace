# product_title_optimizer Worker 工具契约（V1 草案）

## 工具信息

- `toolCode`: `product_title_optimizer`
- 工具名称：`AI 商品标题优化器`
- `resourceType`: `MARKDOWN`
- 目标：根据商品信息、平台场景、目标人群和核心卖点生成商品标题优化方案

## 输入参数（params）

- `productName`（必填）：商品名称
- `category`（必填）：商品类目
- `targetPlatform`（必填）：目标平台
- `targetCustomer`（必填）：目标人群
- `sellingPoints`（必填）：核心卖点
- `keywords`（选填）：希望包含关键词
- `avoidWords`（选填）：避免使用词
- `style`（必填）：标题风格
- `extraInfo`（选填）：补充信息

## 输出最小结构

Markdown 至少包含：

- `## 优化标题`
- `## 推荐标题`
- `## 优化理由`
- `## 关键词建议`
- `## 使用提醒`

## 错误码（复用 Worker 标准）

- `PROMPT_VARIABLE_MISSING`
- `MODEL_CALL_FAILED`
- `MODEL_TIMEOUT`
- `MODEL_OUTPUT_EMPTY`
- `WORKER_INTERNAL_ERROR`
