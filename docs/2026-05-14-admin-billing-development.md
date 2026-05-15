# 2026-05-14 管理后台计费日志与成本看板开发说明

## 范围

本次改造补齐管理后台的模型成本可观测能力：管理员可以配置模型价格，查看今日 token 消耗、平台模型成本、用户扣减算力，以及逐条计费日志。

2026-05-16 已更新计费口径：后台录入和展示改为多数模型官网常见的 `1M tokens` 单位，并兼容图片、视频等按生成次数计费的 API。

## API 变更

契约已同步到 `docs/api/openapi.yml`。

后台计费接口：

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/api/admin/v1/billing/overview` | 查询今日 token 使用量、模型成本、用户扣减算力、调用次数和按模型聚合成本 |
| GET | `/api/admin/v1/billing/usage-logs?pageNo=&pageSize=` | 分页查询模型计费日志 |

后台模型配置接口：

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/api/admin/v1/agent/model-config` | 获取默认模型配置 |
| PUT | `/api/admin/v1/agent/model-config` | 保存默认模型配置 |
| POST | `/api/admin/v1/agent/model-config` | 新增模型配置 |
| GET | `/api/admin/v1/agent/model-config/list` | 查询模型配置列表 |
| PUT | `/api/admin/v1/agent/model-config/{id}` | 更新指定模型配置 |
| DELETE | `/api/admin/v1/agent/model-config/{id}` | 删除指定模型配置 |
| POST | `/api/admin/v1/agent/model-config/{id}/default` | 设置默认模型配置 |
| POST | `/api/admin/v1/agent/model-config/test` | 测试模型配置连通性 |

## 字段变更

`AgentModelConfigRequest` 和 `AgentModelConfigResponse`：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `inputTokenPricePer1m` | decimal | 输入 token 每 1M 平台成本单价 |
| `outputTokenPricePer1m` | decimal | 输出 token 每 1M 平台成本单价 |
| `billingUnit` | string | `TOKEN_PER_M` 或 `PER_CALL` |
| `unitPrice` | decimal | `PER_CALL` 时每个生成单位的平台成本 |
| `consoleUrl` | string | 供应商控制台链接 |
| `balanceUrl` | string | 供应商余额页链接 |
| `docsUrl` | string | 供应商 API 文档链接 |

兼容字段：

| 字段 | 说明 |
| --- | --- |
| `inputTokenPricePer1k` | 老版本字段，暂保留兼容 |
| `outputTokenPricePer1k` | 老版本字段，暂保留兼容 |

`WorkerSuccessRequest`：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `promptTokens` | integer | Worker 实际使用的输入 token 数 |
| `completionTokens` | integer | Worker 实际使用的输出 token 数 |
| `billableUnits` | integer | 图片/视频等按次计费 API 的可计费生成数量 |

`CompleteAgentRunRequest`：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `promptTokens` | integer | Agent run 实际使用的输入 token 数 |
| `completionTokens` | integer | Agent run 实际使用的输出 token 数 |

## 数据库变更

初始化脚本：

- `sql/009_agent_model_configs.sql`：新增供应商入口、1M token 单价、计费单位、按次单价字段。
- `sql/015_billing_usage_logs.sql`：新增 1M token 单价、计费单位、可计费次数、按次单价字段。
- `backend/src/test/resources/schema-test.sql`：同步 H2 测试库结构。

启动补齐：

- `DataInitializer` 会在本地或老库启动时补齐缺失列和 `billing_usage_logs` 表。
- 若旧配置只有 `*_per_1k`，启动时会自动迁移为 `*_per_1m = *_per_1k * 1000`。

## 计费口径

token 成本：

```text
costAmount = promptTokens / 1_000_000 * inputTokenPricePer1m
           + completionTokens / 1_000_000 * outputTokenPricePer1m
```

按次成本：

```text
costAmount += billableUnits * unitPrice
```

记录来源：

| sourceType | 来源 |
| --- | --- |
| `TASK` | Worker 任务成功回写 |
| `AGENT_RUN` | Agent run 完成回写 |

`billing_usage_logs` 是平台成本观测日志，不是支付订单或财务账本。

## 管理后台变更

- 新增页面：`admin-frontend/app/billing/page.tsx`。
- 新增 API client：`admin-frontend/lib/api/billing.ts`。
- 侧边栏新增「计费日志」入口。
- 模型配置页新增输入/输出 token 每 1M 单价。
- 模型配置页新增 `TOKEN_PER_M` / `PER_CALL` 计费单位和按次单价。
- 计费日志列表展示计费单位，能区分 `Token / 1M` 与 `N 次 x 单价`。

## 验证

- 后端：`mvn test` 通过。
- 管理后台：`npm run build` 通过，构建输出包含 `/billing` 路由。
