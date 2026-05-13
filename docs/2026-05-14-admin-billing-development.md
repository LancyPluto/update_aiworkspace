# 2026-05-14 管理后台计费日志与成本看板开发说明

## 范围

本次改造补齐管理后台的模型成本可观测能力：管理员可以配置模型 token 单价，查看今日 token 消耗、平台模型成本、用户扣减算力，以及逐条计费日志。

## API 变更

契约已同步到 `docs/api/openapi.yml`。

新增后台计费接口：

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/api/admin/v1/billing/overview` | 查询今日 token 使用量、模型成本、用户扣减算力、调用次数和按模型聚合成本 |
| GET | `/api/admin/v1/billing/usage-logs?pageNo=&pageSize=` | 分页查询模型计费日志 |

同步补齐后台模型配置接口：

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/api/admin/v1/agent/model-config` | 获取默认 Agent 模型配置 |
| PUT | `/api/admin/v1/agent/model-config` | 保存默认 Agent 模型配置 |
| POST | `/api/admin/v1/agent/model-config` | 新增 Agent 模型配置 |
| GET | `/api/admin/v1/agent/model-config/list` | 查询 Agent 模型配置列表 |
| PUT | `/api/admin/v1/agent/model-config/{id}` | 更新指定模型配置 |
| DELETE | `/api/admin/v1/agent/model-config/{id}` | 删除指定模型配置 |
| POST | `/api/admin/v1/agent/model-config/{id}/default` | 设置默认模型配置 |
| POST | `/api/admin/v1/agent/model-config/test` | 测试模型配置连通性 |

## 字段变更

`AgentModelConfigRequest` 和 `AgentModelConfigResponse` 新增：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `inputTokenPricePer1k` | decimal | 输入 token 每 1k 平台成本单价 |
| `outputTokenPricePer1k` | decimal | 输出 token 每 1k 平台成本单价 |

`WorkerSuccessRequest` 新增可选字段：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `promptTokens` | integer | Worker 实际使用的输入 token 数 |
| `completionTokens` | integer | Worker 实际使用的输出 token 数 |

`CompleteAgentRunRequest` 新增可选字段：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `promptTokens` | integer | Agent run 实际使用的输入 token 数 |
| `completionTokens` | integer | Agent run 实际使用的输出 token 数 |

## 数据库变更

新增脚本：

- `sql/015_billing_usage_logs.sql`：创建 `billing_usage_logs`。

更新脚本：

- `sql/009_agent_model_configs.sql`：为 `agent_model_configs` 增加 `input_token_price_per1k`、`output_token_price_per1k`。
- `backend/src/test/resources/schema-test.sql`：同步 H2 测试库结构。

启动补齐：

- `DataInitializer` 会在本地/老库启动时补齐缺失列和 `billing_usage_logs` 表，便于合并分支后的本地验证。

## 计费口径

成本计算公式：

```text
costAmount = promptTokens / 1000 * inputTokenPricePer1k
           + completionTokens / 1000 * outputTokenPricePer1k
```

当前看板显示的是平台侧模型成本和用户扣减算力，用于运营观察与成本校准，不等同于正式支付订单或用户发票账单。

记录来源：

| sourceType | 来源 |
| --- | --- |
| `TASK` | 传统 Worker 任务成功回写 |
| `AGENT_RUN` | Agent run 完成回写 |

未上报 token 的调用不会写入计费日志，避免用估算值污染成本数据。后续接真实模型 SDK 时，应优先从模型返回的 usage 字段透传。

## 管理后台变更

- 新增页面：`admin-frontend/app/billing/page.tsx`。
- 新增 API client：`admin-frontend/lib/api/billing.ts`。
- 侧边栏新增「计费日志」入口。
- 模型配置页面新增输入/输出 token 单价表单项。
- `admin-frontend` 移除 `next/font/google` 远程字体依赖，避免内网或离线构建时因字体下载失败导致 `npm run build` 失败。

## 验证

- 后端：`mvn test` 通过，`Tests run: 98, Failures: 0, Errors: 0, Skipped: 0`。
- 管理后台：`npm run build` 通过，构建输出包含 `/billing` 路由。

## 后续建议

- 接入真实模型 usage 字段，确保 Worker 和 Agent Service 都按同一口径回传 token。
- 增加按时间范围、用户、模型、来源类型的筛选。
- 增加成本/收入差额、毛利率、异常高消耗告警。
- 充值与支付上线前，另建订单、支付流水、退款流水和对账表，不要把 `billing_usage_logs` 当作财务账本。
