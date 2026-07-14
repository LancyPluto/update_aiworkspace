# 厂商账户与模型分层探活设计

日期：2026-07-14

## 背景

当前统一 API 管理把两类不同问题混在同一条探活链路中：

- 账户级问题：Base URL 是否可达、API Key 或 AK/SK 是否有效。
- 模型级问题：某个模型是否存在、额度是否可用、对应能力 endpoint 是否开放。

生产环境中的阿里云百炼账户已经出现典型误判：HappyHorse 文生视频实际可用，但账户测试调用 Qwen 模型后因免费额度耗尽而把整个账户标记为异常；HappyHorse 模型测试又错误访问 DashScope 根地址下的 `/v1/models`，因此收到 404。

本设计将账户探活和模型探活彻底分层。账户优先使用厂商的模型列表 endpoint 验证网关和凭据，具体模型继续按协议单独测试。

## 目标

1. 支持 `/models` 的账户测试不依赖具体模型，也不产生模型调用费用；仅明确不支持 `/models` 的厂商进入兼容 fallback。
2. 支持 `/models` 的厂商统一使用模型列表接口进行账户探活。
3. 不支持 `/models` 的厂商保留明确的 fallback 策略。
4. 模型测试结果只更新对应模型，不覆盖账户健康状态。
5. 百炼账户在凭据有效时保持正常；Qwen 额度不足只影响 Qwen 模型测试。
6. 不新增数据库表，不改变现有账户和模型健康字段。

## 非目标

- 不在本次改造中统一所有厂商的余额查询。
- 不通过账户探活证明某个模型能力可用。
- `/models` 主路径不创建图片、视频、音乐或文本生成任务；兼容 fallback 保持原有厂商策略语义。
- 不改变真实任务执行、计费、路由或凭据继承逻辑。

## 架构

### 代码注册表

`ConnectivityProbeRegistry` 是 Spring 管理的代码注册表，不是数据库实体。它持有一组 `ProviderConnectivityProbe` 策略，并按照账户厂商、模型 provider、provider protocol 和能力选择探活实现。

注册表不引入动态配置表。新增厂商探活策略时，通过新增 Spring Bean 或扩展 endpoint resolver 完成。

### 探活上下文

账户和模型使用不同上下文，避免账户测试意外携带模型名：

- `AccountProbeContext`：账户 ID、vendor code、Base URL、API Key、额外鉴权 JSON、代理设置。
- `ModelProbeContext`：provider、model name、能力、继承账户后的 Base URL 和凭据、额外鉴权 JSON。

### 统一结果

所有策略返回 `ConnectivityProbeResult`：

- `success`：本层探活是否通过。
- `stage`：`ACCOUNT_MODELS`、`ACCOUNT_FALLBACK` 或 `MODEL_CAPABILITY`。
- `httpStatus`：存在 HTTP 响应时记录状态码。
- `latencyMs`：探活耗时。
- `message`：面向管理端的可读信息。
- `fallbackUsed`：是否使用 fallback。
- `warning`：凭据有效但存在余额不足、免费额度耗尽或限流时的非阻断告警。

现有 API 响应结构保持兼容，由 Service 将统一结果映射到现有 DTO。

## 账户探活

### `/models` 优先

账户探活首先由 `ModelsEndpointResolver` 解析厂商模型列表地址，再由 `ModelsAccountConnectivityProbe` 发起带鉴权的 `GET` 请求。请求不携带模型名。

首批 endpoint 映射：

| 厂商或协议 | endpoint |
|---|---|
| OpenAI 与 OpenAI 兼容中转站 | `<versioned-base>/models`，通常为 `/v1/models` |
| DashScope 标准域名 | `/compatible-mode/v1/models` |
| DashScope 专属 MaaS `/api/v1` | 替换为 `/compatible-mode/v1/models` |
| 火山 Ark | `/api/v3/models` |
| SiliconFlow | `/v1/models` |
| DeepSeek | `/v1/models` |
| Moonshot / Kimi | `/v1/models` |
| 智谱 GLM | `/api/paas/v4/models` |
| Agnes AI | `/v1/models` |
| MiniMax Chat | 优先 `/v1/models`，不支持时 fallback |

### 状态判定

| 响应 | 账户结果 | 行为 |
|---|---|---|
| `2xx` | 正常 | 不解析或验证具体模型名；余额不足通常不影响模型列表请求 |
| `401` | 异常 | 判定凭据无效，不执行 fallback |
| 明确为无效 Key、未授权或签名错误的 `403` | 异常 | 判定凭据无效，不执行 fallback |
| `402`、明确为余额或免费额度耗尽的 `403` | 正常并告警 | Key 已被识别；账户保持正常，余额问题交给余额和模型状态展示 |
| `429` | 正常并告警 | Key 已被识别，消息提示额度或频率限制 |
| `404/405/501` | 未实现 `/models` | 进入厂商 fallback |
| 网络错误、超时、`5xx` | 异常 | 显示网关不可用，不用 fallback 掩盖故障 |

账户探活通过或仅有余额/限流告警时，账户 `health_status` 保持 `OK`，且不得自动关闭账户的 `enabled` 开关。账户探活成功后可沿用现有行为重新启用该账户下因账户未探活而停用的模型，但不得覆盖模型自身的 `last_test_success` 和 `last_test_message`。

`AccountProbeErrorClassifier` 负责结合 HTTP 状态码和响应体分类错误。只有明确的 `invalid api key`、`unauthorized`、`authentication failed`、`signature invalid` 等鉴权语义才归为凭据失败；`insufficient balance`、`quota exhausted`、`free quota exhausted`、`payment required` 和限流语义归为非阻断告警。无法识别原因的 `403` 保守判定为异常。

### Fallback

Fallback 仅在 `/models` 明确不受支持时使用：

- 可灵 Kling：检查 AK/SK 必填与格式，保留现有签名凭据策略，不创建生成任务。
- Suno：保留 accept-only 凭据检查。
- MiniMax 专有音乐、语音能力：账户先尝试 Chat `/models`；无法使用时保留现有策略。
- 其他未注册厂商：沿用当前 `testStrategy`；其中 `agent_service` 可能执行现有轻量模型调用，并在结果中明确标记 `fallbackUsed=true`，避免与无模型的主路径混淆。

`401`、凭据类 `403`、网络错误和 `5xx` 不得进入 fallback，防止把失效 Key 或真实网关故障误判为正常。余额类 `402/403` 和限流 `429` 已视为账户凭据有效，同样不进入 fallback。

## 模型探活

模型探活由模型行的测试按钮触发，只更新该模型的 `last_test_success`、`last_test_message` 和 `last_test_at`。

### 文本模型

- Qwen、DeepSeek、MiniMax Chat、GLM、Kimi、Agnes Chat 继续使用现有轻量模型调用。
- 额度耗尽、模型不存在或能力未开通只标记该模型异常。
- 模型测试不得修改所属账户的 `health_status`。

### OpenAI Images 模型

- 先确认 `/models` 返回列表包含真实模型名。
- 再向图片 endpoint 发送真实模型名但缺少 prompt 的 dry-run。
- 缺少 prompt 等参数校验错误表示能力 endpoint 可达。
- `401/403`、模型不存在、图片能力未开通或中转 group 禁用图片能力表示失败。
- 不再使用 `model=__probe__`，避免假模型提前触发校验并绕过真实权限检查。

### DashScope HappyHorse 模型

- 使用实际模型名请求 `/api/v1/services/aigc/video-generation/video-synthesis`。
- 请求带 `X-DashScope-Async: enable`，但省略生成所需的有效 input，确保不创建可计费任务。
- 缺少 prompt、图片、媒体等必填参数表示 endpoint 和模型路由可达。
- `401/403`、模型不存在、路由不存在或能力未开通表示失败。

### 其他媒体模型

现有 OpenAI Images、Ark、Kling、Suno、Agnes 等媒体协议逐步迁入注册表。首轮改造保留未迁移 provider 的现有测试逻辑作为模型级 fallback，避免扩大上线风险。

## Service 边界

### `ModelVendorAccountServiceImpl`

- 校验账户存在和凭据配置。
- 构造 `AccountProbeContext` 并调用注册表。
- 只更新账户 `health_status`、账户错误消息和更新时间。
- 不选择 linked model，不调用 `agentServiceClient.testModelConfig`。

### `AgentModelConfigServiceImpl`

- 继承厂商账户的凭据并构造 `ModelProbeContext`。
- 调用注册表选择模型协议策略。
- 只更新该模型的连接测试字段。
- 未迁移策略时调用现有模型测试 fallback。

## 管理端行为

不新增按钮或页面结构：

- 账户卡片闪电按钮表示“网关与凭据是否有效”。
- 模型行闪电按钮表示“该模型及能力是否可用”。
- 账户正常但某些模型异常时，厂商汇总仍可显示模型异常数量，但账户卡片不标红。
- 余额不足、免费额度耗尽或限流时，账户卡片保持正常并展示非阻断告警；账户启用开关保持原值。
- Fallback 结果文案明确包含“使用兼容探活策略”，便于后续运营排查。

## 测试

### 账户测试

1. DashScope 根地址解析到 `/compatible-mode/v1/models`。
2. DashScope `compatible-mode/v1` 不重复拼接路径。
3. `/models` 返回 200 时账户正常，且不调用 Agent Service。
4. `/models` 返回 401 或明确凭据错误的 403 时账户异常且不 fallback。
5. `/models` 返回 402、余额类 403 或 429 时账户正常并显示非阻断告警。
6. 无法识别原因的 403 保守判定为账户异常。
7. `/models` 返回 404/405/501 时调用 fallback。
8. `/models` 返回 5xx 或网络错误时不 fallback。
9. 余额类告警不得修改账户 `enabled`，也不得把账户 `health_status` 设为 `ERROR`。

### 模型测试

1. Qwen 模型额度失败只更新模型，不修改账户健康状态。
2. HappyHorse dry-run 使用实际模型名、正确 endpoint 和异步请求头。
3. HappyHorse 缺少必填输入的参数错误判定为可达。
4. HappyHorse 鉴权失败、模型不存在和路由不存在判定为失败。
5. OpenAI Images dry-run 使用真实模型名并识别 group 图片能力禁用。
6. 未迁移厂商继续进入现有 fallback。

## 发布与回滚

- 不执行数据库迁移。
- 先运行 endpoint resolver、账户 API 和模型 API 的聚焦测试，再运行 backend 全量测试。
- 通过现有 CD 流水线同步到生产，不直接修改生产源码或数据库。
- 回滚时恢复两个 Service 的旧探活调用，并删除新注册表与策略类；现有数据库字段无需回滚。
