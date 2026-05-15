# AI 执行平台与模型渠道套餐系统实施状态

日期：2026-05-14

## 当前锁定方案

本项目第一阶段采用“平台底座先行、业务模块可接入”的方式推进：

- RabbitMQ 负责可靠任务队列，优先承接 AI 工具任务、长工作流、AI 漫剧、支付异步处理、后台统计等可靠任务。
- Redis 继续负责缓存、限流、短期状态、会话锁、nonce、验证码、模型节点状态缓存等高速状态。
- 普通国外大模型聊天不走 RabbitMQ，走平台 Model Workbench / Model Gateway 的直连低延迟链路。
- LangChain / LangGraph 继续用于 Agent 编排，不被 LiteLLM 替代。
- LiteLLM、Langfuse、APISIX、PostgreSQL、OSS 自动化、内容审核暂不进入第一版主链路。

## 已落地内容

### 队列底座

- Docker Compose 已加入 RabbitMQ 和管理端口。
- 后端已加入 Spring AMQP，默认可通过 `TASK_QUEUE_BACKEND=rabbitmq` 发布任务。
- worker 已支持 `TASK_QUEUE_BACKEND=rabbitmq` 消费任务。
- RabbitMQ 队列已配置 durable queue、direct exchange、dead-letter exchange、dead-letter queue。
- Redis list 仍保留为 fallback，方便开发者模式和旧链路回退。

### 套餐、支付与渠道池

- 已新增套餐、权益、订阅、订单、支付流水、渠道池、渠道节点、聊天会话、聊天消息、模型调用日志等表结构。
- 启动初始化会自动创建核心 commerce 表并 seed 本地测试套餐和 demo 模型池。
- 支付层已抽象为 `PaymentProvider`，当前启用 `MockPayProvider` 跑通本地闭环。
- `WechatPayProvider` 和 `AlipayProvider` 已作为占位 Provider 保留，等待商户资质、证书和回调域名后启用。

### 模型工作台与调用日志

- 用户端新增 `/models` 模型工作台。
- 未开通模型渠道套餐时，用户端可用 Mock Pay 解锁本地测试套餐。
- 开通后可查看模型池、脱敏节点 ID、节点状态、并发占用、会话历史和消息。
- 后端已记录 `model_call_logs`，并对节点做基础并发占用、日额度检查和释放。
- 管理端新增 `/commerce` 运营面板，展示套餐、模型池、节点、订单、调用日志。
- 管理端侧边栏已加入“模型运营”入口。

### Nginx 与部署

- 已提供可选 Nginx overlay 和配置文件。
- 本地开发默认不强制接入 Nginx，避免影响组员 Docker 联调。
- Nginx 配置已包含 API 反向代理、SSE 不缓冲、上传体积限制、内部接口公网禁用示例。

## 当前第一版边界

以下能力已预留边界，但不是本次第一版完整实现：

- Model Gateway 已具备 OpenAI-compatible 和 Anthropic-compatible 的真实调用能力；当节点未配置 `base_url/api_key` 时会自动回退到 demo response，方便本地开发测试。
- 节点并发和日额度是数据库级基础保护，后续应升级为 Redis 分布式令牌 + DB 最终账本。
- 支付当前只有 Mock 支付闭环，真实微信/支付宝需要接官方 SDK、验签、回调幂等、金额校验和对账。
- RabbitMQ 当前已具备 worker 失败重试、死信队列、管理端队列状态查看和死信重放；后续还需要补延迟退避和更细的队列指标。
- 管理端 `/commerce` 已具备套餐、池、节点基础表单和订单、调用日志、反馈列表；后续还需要补权益编辑、批量补号和快捷上下线。
- LangSmith、Prometheus/Grafana、阿里云监控暂未接入代码，只保留架构位置。

## 组员接入边界

Agent 聊天、AI 工具、AI 漫剧等业务模块后续接入时建议遵守：

- 长耗时、可恢复、需要重试的任务进入 RabbitMQ。
- 低延迟聊天、流式输出走 Model Gateway 直连链路，不进入任务队列。
- 所有模型调用最终写入 `model_call_logs`，便于按用户、模型、节点、供应商统计延迟和失败率。
- 用户套餐、模型池权限、节点并发限制必须在服务端校验，前端只负责展示。
- 前端和用户接口不得返回真实 API Key、账号密码、cookie 或其他渠道凭证。

## 验证命令

```powershell
cd C:\ai\ai-tool-market\backend
mvn -q -DskipTests compile

cd C:\ai\ai-tool-market\user-web
npm run build

cd C:\ai\ai-tool-market\admin-frontend
npm run build
```

Docker 本地联调：

```powershell
cd C:\ai\ai-tool-market\deploy
docker compose up -d
```

可选 Nginx 入口：

```powershell
cd C:\ai\ai-tool-market\deploy
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d
```

## 下一阶段建议

P0：

- Model Gateway 已具备同池 fallback、节点健康标记和基础供应商错误归一化；后续继续补更细的供应商错误分类和 Redis 分布式令牌。
- 为模型调用增加 Redis 分布式并发令牌，保留 DB 作为最终账本。
- 为 RabbitMQ 增加延迟重试、指数退避和更完整的队列指标面板。

P1：

- 管理端补充套餐、权益、渠道池、节点的新增/编辑/上下线表单。
- 统一 AI 工具、Agent service、模型工作台的 `model_call_logs` 写入规范。
- 接入 LangSmith 追踪 Agent LangChain/LangGraph 链路。

P2：

- 接真实微信/支付宝支付。
- 接 Prometheus/Grafana 或阿里云监控。
- StorageService 接阿里云 OSS。
