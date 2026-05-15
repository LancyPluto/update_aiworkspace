# AI 执行基础设施最优执行计划

日期：2026-05-14

本文档基于当前项目代码进度，规划一个不抢占组员业务开发、但能承接 Agent 对话、AI 工具、AI 漫剧等功能的统一执行底座。目标是在开发阶段保持 Docker 本地联调便利，同时逐步补齐任务可靠性、模型调度、并发治理、延迟优化和监控能力。

## 1. 当前项目现状

### 1.1 已具备的基础能力

- 后端已经具备用户、工具、任务、积分、Agent run、Agent event、模型配置等核心表和接口。
- AI 工具执行链路已经打通：用户创建任务，后端写入任务和 outbox，后端发布 Redis 队列，worker 消费任务并回调后端。
- 后端已经有 `task_outbox_events`，可以在 Redis 发布失败时做一定程度的补偿。
- `ai_tasks` 表已经预留 `retry_count`、`max_retry_count`、`idempotency_key`、`queued_at`、`started_at`、`finished_at` 等字段。
- SQL 中已经有 `worker_heartbeats` 表，说明系统已经预留 worker 在线状态能力。
- 管理端已经支持大模型配置，AI 工具也已经可以绑定 `model_config_id`。
- Agent service 已经引入 LangChain、LangGraph、deepagents，具备图执行、预算控制、工具选择、SSE 事件推送等雏形。
- 后端 Agent run 已经有 SSE 事件流，能支撑用户侧实时看到 run event。

### 1.2 当前主要缺口

- Redis 队列当前是 List + `BRPOP`，worker 取出消息后如果崩溃，消息存在丢失风险。
- worker 没有真正上报 `worker_heartbeats`，后端无法判断 worker 是否在线、是否卡死。
- 任务没有锁定字段，例如 `worker_id`、`locked_at`、`lock_expired_at`、`heartbeat_at`，卡死任务难以自动恢复。
- 当前只有一个默认任务队列，文本任务、复杂工具任务、后续图片/视频/漫剧任务容易互相阻塞。
- 模型调用分散：worker 使用原生 HTTP，Agent service 使用 LangChain，管理端测试又是一条链路。
- 没有统一 `model_call_logs`，无法系统化统计模型延迟、失败率、token、成本、供应商稳定性。
- 模型配置只有基础字段，缺少并发、限流、熔断、降级、健康状态等性能治理字段。
- Agent service 中 LangGraph 当前更偏业务编排，没有接入平台级队列、模型调用日志、LangSmith trace 标准。
- Docker compose 当前是开发模式，适合本地开发，但不能直接作为生产部署方案。

## 2. 建议的责任边界

本计划建议将当前要做的内容定义为 `AI Execution Platform`，也就是 AI 执行基础设施层。

这层不负责：

- Agent 聊天具体业务能力。
- AI 漫剧具体生成流程。
- 某个 AI 工具的提示词、表单、业务逻辑。
- 完整短信登录和用户增长业务。

这层负责：

- 任务如何排队、重试、恢复、死信。
- 模型如何选择、调用、限流、降级。
- 高并发下如何保护第三方模型 API、worker、Redis、MySQL。
- 用户如何获得低延迟、可感知的实时进度。
- 管理端如何看到调用记录、失败率、队列积压、worker 状态。
- 给组员提供统一接入规范，让后续业务功能能接入这条执行链路。

## 3. 总体目标架构

```text
User Web / Admin Frontend
        |
        v
Backend API
        |
        +-- Auth / Credit / Tool / Task / Agent Run / Model Config
        |
        +-- Execution Orchestrator
        |       |-- short task direct path
        |       |-- long task queue path
        |       |-- retry / timeout / idempotency
        |
        +-- Model Gateway API / Model Call Log API
        |       |-- timeout / retry / rate limit
        |       |-- fallback / circuit breaker
        |       |-- model_call_logs
        |
        +-- Queue Layer
                |-- Redis Streams initially
                |-- optional RabbitMQ/RocketMQ later

Worker / Agent Service
        |
        +-- AI Tool Worker
        +-- Agent Service with LangChain / LangGraph
        +-- Media Worker later
        |
        v
Model Providers / External APIs
```

## 4. 中间件选型建议

### 4.1 队列中间件

短期建议：继续使用 Redis，但从 Redis List 升级到 Redis Streams。

原因：

- 当前项目已经依赖 Redis，开发和部署成本最低。
- Redis Streams 支持 consumer group、pending entries、ack、claim，比 `BRPOP` 更适合可靠消费。
- 适合当前开发期和早期上线阶段。

建议使用：

```text
XADD ai:stream:text-fast
XGROUP CREATE
XREADGROUP
XACK
XPENDING
XCLAIM / XAUTOCLAIM
```

中期可选：RabbitMQ。

适用场景：

- 需要更成熟的 ack、dead-letter、routing key、延迟重试。
- 团队希望减少自己维护 Redis Streams pending/reclaim 逻辑。

长期可选：RocketMQ / Kafka。

适用场景：

- 任务量非常大。
- 需要更强的消息追踪、分区吞吐、事件流能力。
- 当前阶段不建议引入，复杂度过高。

结论：

```text
当前最优：Redis Streams
备选增强：RabbitMQ
暂不建议：Kafka / RocketMQ
```

### 4.2 并发治理中间件

后端 Java 侧建议：

- `Resilience4j`：熔断、限流、重试、bulkhead、超时控制。
- `Bucket4j`：用户级、接口级、模型级限流。

Python worker / agent-service 侧建议：

- `asyncio.Semaphore`：单进程内模型并发控制。
- Redis token bucket：多 worker 共享的模型级限流。
- `httpx.AsyncClient` 或 `requests.Session` 连接池：减少连接建立延迟。

当前项目 worker 是同步 Python，可以先用简单的 Redis 计数器 + 本地 semaphore，后续如果改成 async worker 再引入 `httpx.AsyncClient`。

### 4.3 监控和可观测中间件

建议组合：

- LangSmith：用于 LangChain / LangGraph 的 Agent trace。
- `model_call_logs`：用于平台自己的模型调用流水、成本、延迟、错误统计。
- Prometheus + Grafana：用于 worker、队列、后端 JVM、Redis、MySQL 指标。
- OpenTelemetry：用于后端、worker、agent-service 之间的 trace id 贯通。
- 阿里云 CloudMonitor / ARMS：后续云端部署时接入主机、容器、网络和应用监控。

注意：LangSmith 不应替代平台监控。它适合看 Agent/LLM trace，不适合负责任务队列、worker 心跳、数据库、Redis 和用户计费审计。

### 4.4 缓存中间件

当前 Redis 可以继续承担缓存。

建议缓存：

- 启用模型配置。
- 工具配置。
- 工具字段 schema。
- 供应商健康状态。
- 模型平均延迟。
- 用户短期限流计数。

不建议缓存：

- 用户私有对话内容。
- 敏感 prompt 明文。
- 完整模型响应，除非未来有明确的内容授权和隐私策略。

### 4.5 文件存储中间件

短期：本地存储。

中期：抽象 `StorageService`。

长期：阿里云 OSS。

AI 漫剧模块未完成前，OSS 优先级不高，但需要提前避免业务代码直接依赖本地路径。

### 4.6 补充中间件优先级评估

截图中的中间件组合适合作为 AI SaaS 的参考，但当前项目已经有 Spring Boot、MySQL、Redis、Python worker、Agent service、LangChain/LangGraph 基础，因此不建议全部照搬。更合理的做法是按“上线阻塞程度、迁移成本、对性能稳定性的收益”分阶段引入。

| 模块 | 推荐组件 | 当前优先级 | 是否加入本次计划 | 结论 |
| --- | --- | --- | --- | --- |
| AI 网关 | LiteLLM Proxy / 自研 Model Gateway | P1 | 是 | 当前先做自研逻辑网关和 `model_call_logs`，后续模型供应商变多后再评估 LiteLLM Proxy。 |
| 缓存/限流 | Redis | P0 | 是 | 已在项目中使用 Redis，应继续承担缓存、限流、并发令牌、队列和 nonce 存储。 |
| API 网关 | Nginx，后续 APISIX | P2 | 是 | 开发期不需要复杂网关；云端部署前至少需要 Nginx 做 TLS、反向代理、静态资源和基础限流。 |
| 数据库 | 继续 MySQL，暂不切 PostgreSQL | P3 | 否 | 当前表结构、SQL、MyBatis 均基于 MySQL，切 PostgreSQL 成本高且收益不紧急。 |
| 队列 | Redis Streams，后续 RabbitMQ；暂不选 BullMQ/Celery | P0 | 是 | 当前 worker 是 Python 但不是 Celery 架构，后端是 Java；Redis Streams 改造成本最低。 |
| 监控 | LangSmith + model_call_logs；可评估 Langfuse | P1 | 是 | LangSmith 适合 LangChain/LangGraph trace；Langfuse 可作为开源 LLM 观测替代，但不能替代平台日志。 |
| 对象存储 | 阿里云 OSS | P3 | 是，但低优先级 | AI 漫剧/文件产物上线前需要 StorageService 抽象，OSS 实现可后置。 |
| 支付 | 微信支付/支付宝 | P2 | 是，作为预留 | 积分体系已存在，正式商业化前需要支付订单、回调幂等、对账和风控。 |
| 内容审核 | 阿里云内容安全/腾讯云内容安全 | P1 | 是 | AI 工具和 Agent 都会产生内容，正式开放前应加入输入/输出审核和敏感内容处置。 |

#### LiteLLM Proxy 是否现在引入

LiteLLM Proxy 的价值是把 OpenAI、Anthropic、DeepSeek、智谱、MiniMax 等多供应商模型统一成一个 OpenAI-compatible 接口，并提供路由、预算、key 管理、日志等能力。

当前建议：不要立刻强制引入 LiteLLM Proxy，但要让自研 `Model Gateway` 的接口设计兼容未来迁移。

原因：

- 当前已经有 `agent_model_configs`，管理端也已经围绕这张表做了 CRUD 和工具绑定。
- worker 已经能按工具读取模型配置并调用模型。
- Agent service 已经用 LangChain 创建模型客户端。
- 现在强行加入 LiteLLM Proxy 会影响本地 Docker 联调和组员开发节奏。

建议路线：

```text
第一阶段：自研 Model Gateway 逻辑层，统一日志、限流、错误码、fallback。
第二阶段：如果供应商数量和调用量上升，再将 LiteLLM Proxy 放到 Model Gateway 后面。
第三阶段：Model Gateway 负责业务治理，LiteLLM Proxy 负责供应商适配。
```

#### Langfuse 和 LangSmith 如何取舍

当前 Agent service 使用 LangChain/LangGraph，所以 LangSmith 接入成本最低，适合先用于 Agent trace。

Langfuse 的优势是开源、自托管、偏 LLM observability 和 prompt/trace 管理，也可以记录非 LangChain 调用。它适合作为后续替代或补充。

建议：

```text
开发期：LangSmith + model_call_logs。
需要自托管或统一非 LangChain 调用时：评估 Langfuse。
无论选谁：平台自己的 model_call_logs 都必须保留。
```

#### BullMQ / Celery 是否适合当前项目

BullMQ 适合 Node.js 项目，当前项目后端是 Java，worker 是 Python，不适合作为主队列。

Celery 适合 Python worker，但它会引入独立任务模型、broker/backend 配置和任务注册体系。当前项目已经有后端任务表、积分冻结、内部回调和 Redis 队列，直接切 Celery 会让后端任务状态和 Celery 状态重复。

因此当前建议：

```text
短期：Redis Streams。
中期：如果需要更成熟的消息中间件，优先 RabbitMQ。
暂不采用：BullMQ / Celery。
```

#### 支付和内容审核为什么要进入计划

支付不是当前性能优化的 P0，但项目已经有积分账户和冻结/结算逻辑。后续商业化时，如果没有提前设计支付订单和回调幂等，会影响积分体系稳定性。

内容审核需要更早考虑。原因是 AI 工具和 Agent 都有用户输入与模型输出，一旦开放给真实用户，就会涉及违规内容、敏感词、营销文本合规和平台责任。

建议先预留：

```text
ContentModerationService
PaymentOrderService
PaymentCallbackService
```

正式接入可以后置，但接口边界应提前留好。

## 5. 模块一：任务队列可靠性

### 5.1 当前问题

当前 worker 使用 Redis List 的 `BRPOP` 消费任务。消息被 pop 后，如果 worker 崩溃，Redis 中已经没有该消息，只能依赖任务状态人工恢复。

后端有 outbox，但 outbox 只保证“发布到 Redis 前”的可靠性，不能保证“worker 消费后”的可靠性。

### 5.2 第一阶段改造

保留现有 `task_outbox_events`，将发布方式从 Redis List 改为 Redis Streams。

新增或补齐任务字段：

```text
ai_tasks.worker_id
ai_tasks.locked_at
ai_tasks.lock_expired_at
ai_tasks.heartbeat_at
ai_tasks.next_retry_at
ai_tasks.queue_name
ai_tasks.task_type
```

任务状态建议统一：

```text
CREATED
QUEUED
PROCESSING
RETRYING
SUCCESS
FAILED
CANCELLED
TIMEOUT
DEAD_LETTER
```

worker 消费流程：

```text
XREADGROUP 读取消息
后端 mark_processing，写 worker_id 和 locked_at
worker 执行业务
成功后回调 mark_success
失败后回调 mark_failed 或 retry
最后 XACK
```

如果 worker 读取后崩溃：

```text
消息留在 pending
watchdog 扫描 XPENDING
超过超时时间后 XAUTOCLAIM 给其他 worker
任务 attempt_count + 1
超过 max_attempts 进入 dead-letter
```

### 5.3 第二阶段改造

拆分队列：

```text
ai:stream:text-fast
ai:stream:tool-normal
ai:stream:agent
ai:stream:media-long
ai:stream:retry
ai:stream:dead-letter
```

调度规则：

- 短文本工具进入 `text-fast`。
- 普通 AI 工具进入 `tool-normal`。
- Agent 后台任务进入 `agent`。
- 图片、视频、AI 漫剧进入 `media-long`。
- 失败待重试任务进入 `retry`。
- 超过重试次数进入 `dead-letter`。

### 5.4 验收标准

- worker 崩溃后任务不会丢失。
- worker 重启后 pending 任务能被重新认领。
- 管理端能看到队列积压、处理中、失败、死信数量。
- 同一个任务重复回调不会重复扣费或重复写结果。

## 6. 模块二：模型调度层 Model Gateway

### 6.1 当前问题

当前模型调用分散：

- worker 通过 `worker/client/model_client.py` 直接 HTTP 请求模型。
- Agent service 通过 LangChain `ChatOpenAI` / `ChatAnthropic` 请求模型。
- 管理端测试模型通过 Agent service 单独测试。

这导致：

- 超时、重试、错误码不统一。
- 模型延迟和失败率无法统一统计。
- 多模型并发限制无法统一生效。
- 后续 fallback、熔断和成本统计难以实现。

### 6.2 第一阶段改造

先不做独立微服务，先做“逻辑网关”。

后端新增：

```text
ModelCallService
ModelCallLogService
ModelHealthService
ModelRoutingService
```

worker 新增：

```text
worker/gateway/model_gateway_client.py
```

agent-service 新增：

```text
app/gateway/model_gateway_client.py
```

短期调用方式：

```text
worker / agent-service
        |
        +-- 读取后端下发的 model_config
        +-- 本地执行模型调用
        +-- 调用后端接口上报 model_call_logs
```

这样改动较小，不阻断组员业务。

### 6.3 第二阶段改造

将模型调用入口逐步收敛为：

```text
callText
callStream
callJson
testConnection
```

统一处理：

```text
provider adapter
timeout
retry
rate limit
circuit breaker
fallback model
error normalization
token usage extraction
cost estimation
model_call_logs
```

### 6.4 模型配置建议新增字段

```text
max_concurrency INT DEFAULT 5
rpm_limit INT NULL
tpm_limit INT NULL
retry_count INT DEFAULT 1
retry_backoff_ms INT DEFAULT 500
fallback_model_config_id BIGINT NULL
streaming_enabled TINYINT DEFAULT 0
health_status VARCHAR(32) DEFAULT 'UNKNOWN'
avg_latency_ms INT NULL
last_success_at DATETIME NULL
last_failure_at DATETIME NULL
```

### 6.5 验收标准

- 任意模型调用都能记录到 `model_call_logs`。
- 管理端可以看到模型平均延迟、失败率、最近错误。
- 单个模型达到并发上限时不会继续无限制请求供应商。
- 主模型失败时可以按配置切换 fallback。

## 7. 模块三：并发治理

### 7.1 治理对象

需要治理的并发不是单一维度，而是多层级：

```text
用户级：单用户每分钟请求数、同时运行任务数
工具级：某个工具同时运行任务数
模型级：某个 model_config 最大并发
供应商级：某个 provider 最大请求数
worker 级：单 worker 最大处理任务数
队列级：不同队列消费速度
后端级：SSE 连接数、接口 QPS
```

### 7.2 建议实现

后端：

```text
RateLimitService
ConcurrencyLeaseService
CircuitBreakerStateService
```

Redis key 示例：

```text
rate:user:{userId}:agent_message
rate:model:{modelConfigId}:rpm
concurrency:model:{modelConfigId}
concurrency:provider:{provider}
circuit:provider:{provider}
```

worker 调模型前：

```text
申请 model_config 并发令牌
申请 provider 并发令牌
执行模型调用
释放令牌
记录成功/失败
失败过多则打开熔断
```

### 7.3 验收标准

- 配置某模型 `max_concurrency=2` 后，同一时间最多 2 个请求进入该模型。
- 第三方模型连续失败后进入短时间熔断。
- 熔断期间请求可以切换 fallback 或快速失败。
- 并发限制不会导致令牌泄漏，超时任务能自动释放令牌。

## 8. 模块四：延迟优化

### 8.1 用户感知目标

AI 平台的体验目标不是只看总耗时，而是：

```text
首字响应时间 < 3 秒
短文本工具 5-15 秒内完成
普通工具有实时进度
长任务明确展示步骤和预计状态
失败时快速反馈，不让用户无感等待
```

### 8.2 短任务直连，长任务入队

建议统一任务分流：

```text
Agent 聊天：direct + streaming
短文本工具：direct 或 text-fast queue
普通 AI 工具：tool-normal queue + progress
AI 漫剧 / 图片 / 视频：media-long queue + step progress
```

当前 Agent 由组员开发，但平台层可以提前提供：

- SSE / WebSocket 事件规范。
- Model Gateway stream 接口。
- model_call_logs 上报接口。
- run event 与 trace id 贯通。

### 8.3 连接和缓存优化

worker：

- 继续复用 `requests.Session`。
- 后续改 async 时使用 `httpx.AsyncClient`。
- 对模型请求设置 connect timeout 和 read timeout。

后端：

- 模型配置缓存 30-60 秒。
- 工具配置和 schema 缓存 60 秒。
- model_call_logs 异步写入或轻量快速写入。

前端：

- 任务状态通过 SSE / WebSocket 推送。
- 避免高频轮询。
- 长任务展示 step progress。

### 8.4 验收标准

- 用户发起短文本工具后能在 1-3 秒内看到“已开始处理”。
- 支持 stream 的模型可以边生成边展示。
- 长任务不会阻塞短任务队列。
- 模型日志写入不明显增加用户等待时间。

## 9. 模块五：监控与可观测

### 9.1 平台级日志表

新增 `model_call_logs`：

```text
id
trace_id
user_id
task_id
agent_run_id
tool_id
model_config_id
provider
model_name
request_type
streaming
status
error_code
error_message
latency_ms
prompt_tokens
completion_tokens
total_tokens
estimated_cost
started_at
finished_at
created_at
```

新增或完善 `task_execution_logs`：

```text
id
task_id
worker_id
event_type
event_message
event_json
created_at
```

### 9.2 指标体系

模型指标：

- 调用次数。
- 成功率。
- P50/P95/P99 延迟。
- 超时次数。
- 限流次数。
- fallback 次数。
- 熔断次数。

队列指标：

- 队列积压数。
- pending 数。
- processing 数。
- dead-letter 数。
- 平均等待时间。
- 平均执行时间。

worker 指标：

- 在线 worker 数。
- worker 心跳时间。
- 当前处理任务数。
- 成功/失败任务数。
- 崩溃恢复次数。

业务指标：

- 工具调用排行。
- 工具失败排行。
- 用户调用排行。
- 积分消耗。
- Agent run 成功率。

### 9.3 LangSmith 接入策略

Agent service 已经使用 LangChain / LangGraph，适合接 LangSmith。

建议配置：

```text
LANGSMITH_TRACING=true
LANGSMITH_API_KEY=xxx
LANGSMITH_PROJECT=ai-tool-market-dev
```

接入原则：

- 只在 Agent service 默认开启开发环境 trace。
- 生产环境需要脱敏、采样、按项目隔离。
- prompt/response 可能包含用户隐私，不应无脑全量上传。
- worker 当前原生 HTTP 调模型，不会天然进入 LangSmith，需要用 `model_call_logs` 兜底。

### 9.4 验收标准

- 管理端能按模型、工具、用户、任务查看调用记录。
- 模型失败时能看到错误码和供应商响应摘要。
- Agent 复杂执行可以在 LangSmith 中追踪。
- Redis、worker、后端、模型供应商的瓶颈能被定位。

## 10. 组员功能接入规范

### 10.1 Agent 对话接入

组员继续实现 Agent 业务逻辑，平台层提供：

```text
Agent run 创建
SSE 事件通道
Model Gateway / model_call_logs 接口
LangSmith trace 配置
并发和预算限制
```

推荐链路：

```text
User Web
-> Backend create agent run
-> Agent service LangGraph
-> Model Gateway client
-> Model Provider
-> Backend append run events
-> SSE 推给前端
```

### 10.2 AI 工具接入

组员只需要关注工具参数和结果格式。

推荐链路：

```text
User Web
-> Backend create tool task
-> Execution Orchestrator 选择队列
-> Worker 执行业务
-> Model Gateway client 调模型
-> Backend mark success/failed
-> Frontend 通过任务状态或 SSE 获取结果
```

### 10.3 AI 漫剧接入

AI 漫剧属于长流程任务，建议后续使用 LangGraph 或显式 step 状态机。

推荐步骤：

```text
脚本生成
分镜生成
角色设定
图片生成
音频生成
视频合成
产物归档
```

平台层提供：

- `media-long` 队列。
- step progress。
- checkpoint / resume 规范。
- StorageService 接口，后续切 OSS。

## 11. 最优执行路线

### P0：不影响组员开发的底座补齐

预计 2-4 天。

- 新增 `model_call_logs` 表和后端内部上报接口。
- worker 模型调用后上报调用日志。
- agent-service 模型调用后上报调用日志。
- 模型配置增加并发、fallback、health、streaming 相关字段。
- worker 增加 `worker_id` 配置和基础 heartbeat 上报。
- 管理端增加模型调用日志基础列表。
- Redis 继续作为缓存、限流计数、并发令牌和队列基础中间件。
- 预留 `ContentModerationService` 接口，不要求立即接云厂商。

### P1：任务可靠性升级

预计 4-7 天。

- Redis List 升级为 Redis Streams。
- 增加 consumer group。
- worker 成功后 `XACK`。
- 增加 pending reclaim watchdog。
- 任务增加 locked/heartbeat/attempt 字段。
- 增加 retry 和 dead-letter 流程。
- 管理端展示队列积压和死信任务。

### P2：并发治理和降级

预计 4-6 天。

- 增加模型级并发令牌。
- 增加 provider 级限流。
- 增加简单熔断状态。
- 增加 fallback model 选择。
- 模型连续失败时自动降级或快速失败。
- 管理端展示模型健康状态、平均延迟、失败率。
- 增加内容审核接入点：用户输入审核、模型输出审核、失败处置策略。
- 对 LiteLLM Proxy 做技术验证，但不阻塞当前自研 Model Gateway。

### P3：延迟优化和实时体验

预计 4-7 天。

- 定义 direct path 和 queue path 分流规则。
- 短文本工具接入 text-fast queue 或直连。
- 增加任务进度 SSE / WebSocket 规范。
- 支持模型 streaming 的统一事件格式。
- model_call_logs 写入异步化或轻量化。
- 工具配置、模型配置、schema 加 Redis 缓存。

### P4：LangSmith 和云端监控准备

预计 2-4 天。

- Agent service 接 LangSmith。
- 对 Langfuse 做自托管可行性评估，作为 LangSmith 的备选或补充。
- 增加 trace id 在 backend、worker、agent-service 之间传递。
- 输出 Prometheus 指标。
- 增加生产 compose 示例，不替换当前开发 compose。
- 增加 Nginx 反向代理示例配置，用于云端 TLS、静态资源、API 转发和基础限流。
- 后续接阿里云 CloudMonitor / ARMS。

### P5：商业化和合规预留

预计 3-5 天，可与业务上线节奏并行。

- 设计支付订单表、支付流水表和支付回调幂等键。
- 预留微信支付、支付宝支付 Provider 接口。
- 积分充值和支付回调只做接口边界，不影响当前本地开发。
- 设计内容审核记录表，记录命中策略、处置结果和人工复核状态。
- 设计 StorageService 接口，为后续阿里云 OSS 接入做准备。

## 12. 当前不建议做的事项

- 不建议现在把开发 Docker compose 改成生产模式。
- 不建议现在引入 Kafka / RocketMQ。
- 不建议现在引入 BullMQ，当前项目不是 Node.js 后端架构。
- 不建议现在切 Celery，避免和已有 Java 后端任务表、积分结算、内部回调形成双状态系统。
- 不建议强行重构组员正在做的 Agent 聊天业务。
- 不建议现在完整接入 OSS。
- 不建议现在把所有模型调用改成独立微服务。
- 不建议把 LangSmith 当成唯一监控系统。
- 不建议现在从 MySQL 切换 PostgreSQL，迁移成本高且不是当前性能瓶颈。
- 不建议现在强制引入 APISIX，Nginx 足够覆盖早期云端部署。

## 13. 近期建议落地的具体文件方向

后端：

```text
backend/src/main/java/.../execution/
backend/src/main/java/.../model/
backend/src/main/java/.../observability/
backend/src/main/java/.../task/
sql/014_model_call_logs.sql
sql/015_task_reliability_upgrade.sql
```

worker：

```text
worker/gateway/model_gateway_client.py
worker/task_queue/redis_stream_consumer.py
worker/runtime/heartbeat.py
worker/runtime/concurrency_limiter.py
```

agent-service：

```text
agent-service/app/gateway/model_gateway_client.py
agent-service/app/observability/langsmith.py
agent-service/app/observability/tracing.py
```

管理端：

```text
admin-frontend/app/model-call-logs/
admin-frontend/app/queue-monitor/
admin-frontend/components/admin/model-health-panel.tsx
```

## 14. 最终判断

当前项目已经具备“能跑通 AI 工具和 Agent 雏形”的基础，但还缺少面向多人使用和云端部署的执行底座。最优策略不是继续堆业务功能，而是先做 `AI Execution Platform`：

```text
任务可靠性
模型调度
并发治理
延迟优化
监控可观测
组员接入规范
```

这样既不会干扰组员开发 Agent 聊天和 AI 漫剧，也能让他们后续开发出的功能自然接入统一的执行链路。等业务模块越来越多，这一层会成为项目稳定性、用户体验和云端部署能力的核心。
