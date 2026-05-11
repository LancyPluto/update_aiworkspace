# 云端通用 Agent 系统项目总规划

> 日期：2026-05-10
> 项目：AI Tool Market / AI 工具超市
> 目标：在现有 AI 工具超市基础上，引入一个类似网页版 GPT 的云端通用 Agent 系统，让用户通过自然语言完成聊天、工具调用、知识库问答、文件分析和复杂多步骤任务。

---

## 1. 背景与目标

当前项目已经具备 AI 工具超市的核心雏形：

- 用户端：`user-web`，Vue 3 + Vite，用于工具浏览、任务提交、任务状态和结果查看。
- 管理端：`admin-frontend`，Next.js + React，用于用户、工具、任务、算力和 Prompt 管理。
- 业务后端：`backend`，Spring Boot 3 + Java 17 + MyBatis-Plus，负责认证、工具、任务、算力、内部接口和队列发布。
- Worker：`worker`，Python 消费 Redis 队列，执行 Prompt 拼装、模型调用和结果回写。
- 基础设施：MySQL、Redis、Docker Compose，已有内部接口 HMAC 签名机制。

新的目标不是只新增一个聊天窗口，而是建设一个“云端通用 Agent 平台”：

- 用户只需要自然语言表达目标。
- 系统自动识别用户意图。
- 系统自动选择合适执行路径：普通聊天、调用工具、检索知识库、分析文件、拆解复杂任务。
- 前端以 ChatGPT 式体验呈现，但后端保持工具市场、任务系统、算力计费、权限审计等平台能力。
- 系统需要从第一天就考虑高并发、可用性、安全性和后续 Kubernetes 迁移。

---

## 2. 产品定位

云端通用 Agent 是 AI 工具超市的统一入口。

传统模式：

```text
用户选择工具 -> 填表 -> 提交任务 -> 等待结果
```

目标模式：

```text
用户提出目标 -> Agent 理解意图 -> 自动选择工具/知识库/工作流 -> 流式返回过程和结果
```

第一期不要求 Agent 解决所有复杂问题，但必须让用户感受到：

- 它能理解自然语言意图。
- 它能自动使用平台已有工具。
- 它能解释自己正在做什么。
- 它能保存上下文并持续协作。
- 它受平台权限、算力和安全策略约束。

---

## 3. 范围定义

### 3.1 第一阶段必须覆盖

- 通用 Agent Chat 页面。
- 会话创建、历史消息、上下文保存。
- 流式输出。
- 意图识别。
- 自动调用现有 AI 工具。
- 工具调用过程可视化。
- 算力预估与扣费。
- 基础用户级限流。
- 基础审计日志。
- 内部服务签名调用。

### 3.2 中期覆盖

- 文件上传与文件分析。
- 私有知识库和 RAG 问答。
- 多步骤工作流。
- 长任务异步执行。
- 失败重试与断点续跑。
- Agent 运行可观测性。

### 3.3 后期覆盖

- 多 Agent 协作。
- 企业级知识库权限。
- 插件化工具生态。
- Kubernetes 弹性扩缩容。
- 多模型路由。
- 复杂风控和内容安全。

### 3.4 暂不纳入第一阶段

- 开放用户自定义任意代码执行。
- 完整插件市场。
- 企业多租户隔离。
- Kubernetes 生产集群。
- 完整 ELK 和全链路大屏。
- 高复杂度多 Agent 自治协作。

---

## 4. 总体技术架构

目标技术栈：

- 前端：Next.js + React + TypeScript + Tailwind + shadcn/ui。
- 业务后端：Spring Boot 3 + MyBatis-Plus + Spring Security 或 Sa-Token。
- Agent 服务：FastAPI + LangGraph + LangChain。
- 任务系统：Redis + RabbitMQ / Celery。
- 数据库：MySQL / PostgreSQL。
- 向量数据库：Qdrant / Milvus / pgvector。
- 文件存储：MinIO / 阿里云 OSS / 腾讯 COS。
- 日志监控：LangSmith + Prometheus + Grafana + ELK。
- 部署：Docker Compose，后期 Kubernetes。

推荐整体结构：

```text
Browser
  |
  | HTTPS / SSE / WebSocket
  v
Next.js Agent Web
  |
  | REST / SSE
  v
Spring Boot Business Backend
  |  - Auth / User / Credit / Tool / Session / Audit
  |  - API Gateway for Agent business operations
  |  - Internal request signing
  |
  | Internal API / MQ Event
  v
FastAPI Agent Service
  |  - Intent Router
  |  - Agent Planner
  |  - LangGraph Runtime
  |  - Tool Registry
  |  - RAG Retriever
  |  - Model Gateway
  |
  +--> Redis
  +--> RabbitMQ / Celery
  +--> MySQL / PostgreSQL
  +--> Qdrant / pgvector
  +--> MinIO / OSS / COS
  +--> LangSmith / Prometheus / ELK
```

---

## 5. 核心设计原则

### 5.1 Spring Boot 是业务事实源

Spring Boot 后端继续负责：

- 用户身份认证。
- 用户权限。
- 算力账户。
- 工具定义。
- 任务记录。
- Agent 会话元数据。
- 文件归属。
- 审计日志。
- 内部服务访问控制。

Agent 服务不能绕过业务后端直接修改用户算力、任务状态或权限数据。

### 5.2 FastAPI Agent Service 是智能执行层

Agent 服务负责：

- 意图识别。
- 任务规划。
- LangGraph 状态机执行。
- LangChain 工具调用。
- RAG 检索。
- 模型调用。
- 流式生成。
- 长任务执行。

Agent 服务可以保存执行中状态，但最终业务状态必须回写 Spring Boot。

### 5.3 所有工具调用必须受控

Agent 只能调用平台注册过的工具。

每个工具必须有：

- 工具编码。
- 名称和描述。
- 输入 JSON Schema。
- 输出格式。
- 权限策略。
- 算力消耗规则。
- 是否允许 Agent 自动调用。
- 是否需要用户二次确认。

### 5.4 用户体验是一个入口，多种执行模式

用户不需要选择“聊天 / 工具 / RAG / 工作流”。

统一入口：

```text
用户输入自然语言
  -> Agent 识别意图
  -> Agent 选择执行模式
  -> 前端展示执行过程
  -> 返回最终结果
```

---

## 6. Agent Kernel 设计

Agent Kernel 是通用 Agent 的核心。

### 6.1 执行流程

```text
User Message
  |
  v
Context Loader
  |
  v
Intent Router
  |
  +--> General Chat
  +--> Tool Agent
  +--> RAG Agent
  +--> File Analysis Agent
  +--> Workflow Agent
  +--> Hybrid Agent
  |
  v
Execution Guard
  |
  v
LangGraph Runtime
  |
  v
Result Streamer
  |
  v
Persistence / Billing / Audit
```

### 6.2 Intent Router 输入

- 当前用户消息。
- 会话历史。
- 当前页面上下文。
- 用户上传文件。
- 用户可访问知识库。
- 平台可用工具列表。
- 用户权限。
- 用户算力余额。
- 当前模型能力。
- 历史偏好。

### 6.3 Intent Router 输出

```json
{
  "intent": "tool_use",
  "confidence": 0.86,
  "needClarification": false,
  "selectedTools": ["xiaohongshu_copywriting"],
  "executionMode": "sync_stream",
  "estimatedCredits": 5,
  "reason": "用户明确要求生成小红书种草文案"
}
```

### 6.4 执行模式

#### General Chat

普通对话，不调用外部工具，适合解释、建议、轻量创作。

#### Tool Agent

自动选择并调用 AI 工具超市已有工具，适合结构化生成任务。

#### RAG Agent

基于知识库检索回答，适合文档、资料、企业知识场景。

#### File Analysis Agent

对用户上传文件做解析、摘要、提取、对比、问答。

#### Workflow Agent

把复杂目标拆成多个步骤，自动调用多个工具，最后汇总结果。

#### Hybrid Agent

先对话澄清，再调用工具或知识库，适合需求不明确的场景。

---

## 7. 前端规划

### 7.1 前端技术路线

当前用户端是 Vue 3，管理端是 Next.js。目标技术栈要求前端采用：

```text
Next.js + React + TypeScript + Tailwind + shadcn/ui
```

建议新增独立前端应用：

```text
agent-web/
```

理由：

- 不破坏现有 `user-web`。
- 可以按目标技术栈从零设计 ChatGPT 式体验。
- 后续如果决定迁移用户端，可以逐步把 `user-web` 功能合并进 `agent-web`。
- Agent UI 与传统工具市场 UI 交互范式差异较大，独立模块更清晰。

### 7.2 核心页面

- 登录页复用现有用户体系。
- Agent 首页。
- 会话列表。
- 会话详情。
- 文件上传区。
- 工具执行卡片。
- 知识库选择器。
- 运行历史。
- 余额和消耗提示。

### 7.3 Agent 对话界面

界面组成：

- 左侧：会话列表、搜索、创建会话。
- 中间：消息流、工具调用过程、结果卡片。
- 底部：输入框、文件上传、模式提示。
- 右侧可选：运行详情、引用来源、工具调用详情。

### 7.4 前端状态

需要支持：

- 流式 token 追加。
- Agent 当前状态展示。
- 工具调用状态展示。
- 文件上传进度。
- 失败重试。
- 会话切换。
- 长任务恢复。

---

## 8. Spring Boot 业务后端规划

### 8.1 新增 Agent 模块

建议新增包：

```text
backend/src/main/java/com/aiminilab/aitoolmarket/agent/
  controller/
  service/
  mapper/
  dto/
  entity/
```

### 8.2 业务职责

Spring Boot 负责：

- 创建 Agent 会话。
- 保存用户消息。
- 创建 Agent Run。
- 校验用户权限和余额。
- 调用 Agent Service。
- 接收 Agent Service 回写。
- 记录工具调用。
- 记录算力消耗。
- 管理文件元数据。
- 管理知识库元数据。
- 管理审计日志。

### 8.3 API 示例

```text
POST   /api/v1/agent/sessions
GET    /api/v1/agent/sessions
GET    /api/v1/agent/sessions/{sessionId}
POST   /api/v1/agent/sessions/{sessionId}/messages
GET    /api/v1/agent/runs/{runId}
POST   /api/v1/agent/runs/{runId}/cancel
POST   /api/v1/agent/files
GET    /api/v1/agent/files
```

流式输出可以有两种方式：

方案 A：

```text
Browser -> Spring Boot SSE -> Agent Service
```

方案 B：

```text
Browser -> Spring Boot 创建 Run
Browser -> Agent Service SSE，携带短期签名 token
```

第一期建议使用方案 A，安全边界简单；后期高并发时可切到方案 B。

---

## 9. FastAPI Agent Service 规划

### 9.1 目录结构

```text
agent-service/
  app/
    main.py
    api/
      chat.py
      runs.py
      internal.py
    core/
      intent_router.py
      planner.py
      runtime.py
      cost_guard.py
      event_stream.py
    graphs/
      universal_agent_graph.py
      tool_agent_graph.py
      rag_agent_graph.py
      file_analysis_graph.py
      workflow_agent_graph.py
    tools/
      registry.py
      backend_tool.py
      tool_contract.py
    rag/
      loader.py
      splitter.py
      retriever.py
      embedding.py
    clients/
      backend_client.py
      model_client.py
      vector_client.py
      storage_client.py
    security/
      signature.py
      policy.py
    observability/
      tracing.py
      metrics.py
  tests/
  requirements.txt
  Dockerfile
```

### 9.2 LangGraph 状态设计

Agent State 至少包含：

```text
run_id
session_id
user_id
messages
intent
selected_tools
uploaded_files
knowledge_base_ids
plan_steps
tool_results
retrieved_chunks
final_answer
cost
errors
```

### 9.3 关键节点

- `load_context`
- `classify_intent`
- `maybe_clarify`
- `plan_execution`
- `check_policy`
- `execute_tool`
- `retrieve_knowledge`
- `analyze_file`
- `execute_workflow_step`
- `synthesize_answer`
- `persist_result`

---

## 10. 任务系统规划

### 10.1 当前状态

当前项目使用 Redis List：

```text
ai:task:queue
```

后端通过 `TaskQueuePublisher` 推送任务，Python Worker 消费。

### 10.2 目标状态

短任务：

- 通过 HTTP + SSE 直接流式返回。
- 适合普通聊天、简单工具调用。

长任务：

- 进入 RabbitMQ。
- Celery Worker 执行。
- Spring Boot 和 Agent Service 共同维护 run 状态。
- 前端通过轮询或 SSE 获取状态。

### 10.3 队列分层

```text
agent.chat.realtime
agent.tool.short
agent.workflow.long
agent.file.parse
agent.embedding.build
agent.retry
agent.dead_letter
```

### 10.4 可靠性要求

- 所有任务必须有 `runId`。
- 所有任务必须幂等。
- 消费失败必须记录错误。
- 可重试错误进入 retry 队列。
- 不可重试错误进入 failed 状态。
- 超过最大重试进入 dead letter。

---

## 11. 数据库规划

### 11.1 建议第一阶段新增表

```text
agent_sessions
agent_messages
agent_runs
agent_tool_calls
agent_run_events
```

### 11.2 第二阶段新增表

```text
agent_files
knowledge_bases
knowledge_documents
knowledge_chunks
```

### 11.3 表职责

`agent_sessions`：

- 会话归属。
- 会话标题。
- 最近更新时间。
- 是否归档。

`agent_messages`：

- 用户消息。
- Assistant 消息。
- 系统摘要。
- 引用结果。

`agent_runs`：

- 每次 Agent 执行。
- 状态。
- 意图。
- 模型。
- 算力。
- 错误。

`agent_tool_calls`：

- 工具调用过程。
- 入参。
- 出参。
- 状态。
- 耗时。

`agent_run_events`：

- 流式过程事件。
- 前端可恢复运行过程。
- 后续审计和排障。

---

## 12. 向量数据库与知识库规划

### 12.1 技术选择

第一期不强制引入向量库。

第二期建议：

- 小规模：PostgreSQL + pgvector。
- 中等规模：Qdrant。
- 大规模或多模态扩展：Milvus。

如果当前业务数据库继续使用 MySQL，则建议向量库用 Qdrant，避免为了 pgvector 额外迁移主库。

### 12.2 RAG 流程

```text
文件上传
  -> 文件存储
  -> 元数据入库
  -> 文本解析
  -> 切片
  -> embedding
  -> 向量入库
  -> 检索
  -> rerank
  -> answer synthesis
```

### 12.3 权限原则

- 向量 chunk 必须绑定 `user_id` 或 `knowledge_base_id`。
- 检索前必须由 Spring Boot 确认可访问范围。
- Agent Service 不得跨用户检索。

---

## 13. 文件存储规划

### 13.1 存储选择

开发环境：

- MinIO。

生产环境：

- 阿里云 OSS。
- 腾讯 COS。
- 或继续使用自建 MinIO 集群。

### 13.2 文件类型

第一批支持：

- PDF。
- Word。
- TXT / Markdown。
- CSV。
- 图片。

后续支持：

- Excel。
- PPT。
- 音频。
- 视频。

### 13.3 安全要求

- 文件大小限制。
- 文件 MIME 校验。
- 文件扩展名校验。
- 私有桶。
- 临时签名 URL。
- 上传后异步解析。
- 可选病毒扫描。

---

## 14. 高并发设计

### 14.1 流量入口

生产建议：

```text
Nginx / Ingress
  -> Frontend
  -> Spring Boot API
  -> Agent Service
```

### 14.2 限流策略

至少需要四层限流：

- IP 限流。
- 用户限流。
- 接口限流。
- Agent Run 并发限流。

示例：

```text
free_user:
  max_active_runs: 1
  max_messages_per_minute: 10
  max_file_size_mb: 10

paid_user:
  max_active_runs: 5
  max_messages_per_minute: 60
  max_file_size_mb: 100
```

### 14.3 削峰策略

- 短请求直接处理。
- 长任务入队。
- 高峰期限制新 run 创建。
- 超预算任务排队或拒绝。
- 模型接口超时时降级。

### 14.4 SSE / WebSocket 扩展

SSE 长连接会占用服务资源。

建议：

- 第一阶段通过 Spring Boot 代理 SSE。
- 第二阶段引入专门的 streaming gateway。
- Agent 事件写入 Redis Stream 或 `agent_run_events`。
- 前端断线后可恢复历史事件。

### 14.5 数据库性能

- 所有列表接口分页。
- `agent_messages` 按 session_id 建索引。
- `agent_runs` 按 user_id、status、created_at 建索引。
- 大消息体可考虑对象存储，只在数据库存摘要。
- 长期归档历史会话。

---

## 15. 可用性设计

### 15.1 服务降级

当 Agent Service 异常：

- 传统工具市场仍可用。
- 用户能看到“Agent 暂不可用”。
- 未完成 run 标记为 failed 或 timeout。

当模型供应商异常：

- 重试。
- 切换备用模型。
- 返回可恢复错误。

当向量库异常：

- RAG 功能降级。
- 普通 Chat 和工具调用仍可用。

### 15.2 任务恢复

- `agent_runs` 保存运行状态。
- `agent_run_events` 保存过程事件。
- 长任务 Worker 重启后可根据 run 状态恢复或失败重试。

### 15.3 超时控制

必须设置：

- 单次模型调用超时。
- 单次工具调用超时。
- 单个 Agent Run 最大执行时间。
- 单个 Workflow 最大步骤数。

---

## 16. 安全设计

### 16.1 认证与授权

- 外部用户请求必须走 Spring Boot JWT / Spring Security / Sa-Token。
- Agent Service 不直接信任浏览器。
- Agent Service 内部接口必须使用 HMAC 签名。
- 所有 Agent 操作必须绑定 user_id。

### 16.2 工具安全

- 工具必须注册。
- 工具入参必须 Schema 校验。
- 敏感工具需要用户二次确认。
- Agent 不允许执行任意代码。
- Agent 不允许访问未授权 URL 或内部网络。

### 16.3 Prompt Injection 防护

- 系统指令、用户输入、检索内容分层。
- 检索内容必须标记为“不可信上下文”。
- 工具调用前执行策略检查。
- 模型输出不能直接变成高权限操作。

### 16.4 数据隔离

- 会话、文件、知识库、向量数据都必须绑定用户或租户。
- 查询条件必须包含 user_id 或授权范围。
- 后台管理接口必须单独授权。

### 16.5 审计

必须记录：

- 用户输入摘要。
- Agent 意图判断。
- 工具调用。
- 文件访问。
- 知识库检索范围。
- 算力扣费。
- 错误和异常。

---

## 17. 算力与计费规划

### 17.1 第一阶段

采用简单计费：

- 每次 Agent Run 预估冻结算力。
- 完成后按实际消耗结算。
- 失败时释放未使用算力。
- 超预算时停止执行。

### 17.2 消耗来源

- 模型输入 token。
- 模型输出 token。
- 工具调用。
- 文件解析。
- embedding。
- 工作流步骤。

### 17.3 防止失控

- 免费用户单 run 最大消耗。
- 付费用户单 run 最大消耗。
- 全局最大工具调用次数。
- 全局最大模型调用次数。

---

## 18. 可观测性规划

### 18.1 TraceId

所有链路必须有统一 TraceId：

```text
Browser -> Spring Boot -> Agent Service -> Model Provider -> Tool -> Database
```

### 18.2 LangSmith

用于追踪：

- Prompt。
- LangGraph 节点。
- 工具调用。
- 模型耗时。
- token 使用。
- 错误节点。

### 18.3 Prometheus + Grafana

关键指标：

- API QPS。
- Agent Run 创建量。
- Agent Run 成功率。
- 平均首 token 时间。
- 平均完成时间。
- 模型错误率。
- 队列堆积。
- Celery Worker 数量。
- Redis 连接数。
- 数据库慢查询。

### 18.4 ELK

日志内容：

- 业务日志。
- Agent 执行日志。
- 安全审计日志。
- 错误日志。

---

## 19. 部署规划

### 19.1 开发环境

使用 Docker Compose：

```text
mysql
redis
rabbitmq
minio
qdrant
backend
agent-service
agent-web
worker
```

### 19.2 测试环境

- 独立数据库。
- 独立对象存储 bucket。
- 模型 mock 和真实模型可切换。
- 压测脚本。

### 19.3 生产环境

初期：

- Docker Compose 或单机多容器。
- Nginx 反向代理。
- 数据库独立部署。
- Redis 独立部署。

后期：

- Kubernetes。
- HPA 自动扩缩容。
- 独立 Agent Worker Deployment。
- 独立 Streaming Gateway。
- Secret Manager。
- 灰度发布。

---

## 20. 分阶段里程碑

### Phase 0：架构准备

目标：固定技术边界和接口契约。

交付：

- 总体架构文档。
- Agent 模块数据模型草案。
- Agent Service 接口草案。
- 第一阶段开发计划。

### Phase 1：Cloud Universal Agent MVP

目标：实现通用聊天 + 意图识别 + 自动工具调用。

交付：

- `agent-service` FastAPI 项目。
- Spring Boot Agent 会话和 Run API。
- Agent Chat 前端。
- SSE 流式输出。
- 自动调用 2-3 个现有工具。
- 算力冻结和结算。
- 基础限流和审计。

### Phase 2：文件分析与知识库

目标：支持文件上传、解析、检索和问答。

交付：

- 文件上传。
- MinIO / OSS 存储。
- 文件解析任务。
- 向量库接入。
- RAG Agent。
- 引用来源展示。

### Phase 3：多步骤工作流 Agent

目标：支持复杂目标拆解和多工具协作。

交付：

- Workflow Planner。
- LangGraph 多节点流程。
- 工具执行链。
- 中途确认。
- 结果汇总。
- 长任务队列。

### Phase 4：可靠性与高并发增强

目标：让系统具备生产可用性。

交付：

- RabbitMQ。
- Celery Worker。
- 死信队列。
- 断点恢复。
- 高并发限流。
- SSE 恢复。
- 压测和容量评估。

### Phase 5：安全治理与可观测性

目标：完善安全、审计、监控和运维。

交付：

- Prompt Injection 防护。
- 工具安全策略。
- 文件安全策略。
- LangSmith。
- Prometheus + Grafana。
- ELK。
- 审计后台。

### Phase 6：平台化与 Kubernetes

目标：演进为可扩展 Agent 平台。

交付：

- Kubernetes 部署。
- 多模型路由。
- 插件化工具注册。
- 多租户隔离。
- 企业知识库权限。
- 自动扩缩容。

---

## 21. 第一阶段建议拆分

后续第一阶段可以拆成以下开发文档：

1. Agent Service MVP 开发文档。
2. Spring Boot Agent 业务模块开发文档。
3. Agent Chat 前端开发文档。
4. 工具注册与自动调用开发文档。
5. 流式输出与运行事件开发文档。
6. 算力计费与限流开发文档。
7. 基础安全与审计开发文档。

每份开发文档都应该包含：

- 目标。
- 影响范围。
- 数据表。
- API。
- 文件结构。
- 任务步骤。
- 测试策略。
- 验收标准。

---

## 22. 主要风险

### 22.1 范围过大

风险：一开始同时做 Chat、RAG、Workflow、文件、监控、K8s，导致周期过长。

应对：第一阶段只做 Agent MVP，RAG 和 Workflow 后置。

### 22.2 Agent 不可控

风险：Agent 自动调用错误工具、消耗过多算力、执行危险动作。

应对：工具白名单、Schema 校验、权限校验、预算上限、敏感操作二次确认。

### 22.3 流式连接拖垮服务

风险：大量 SSE 连接占满服务线程或连接池。

应对：限制并发 run，拆分 streaming gateway，使用事件恢复机制。

### 22.4 数据泄漏

风险：RAG 或文件检索跨用户访问。

应对：所有数据绑定 user_id / tenant_id，检索前做授权过滤。

### 22.5 成本失控

风险：复杂 Agent 循环调用模型和工具。

应对：最大步骤数、最大 token、最大耗时、最大工具调用次数。

---

## 23. 推荐下一步

下一步不要直接开写代码，应该先确认第一阶段的产品和技术边界，然后写第一份细分开发文档。

建议优先细化：

```text
Phase 1：Cloud Universal Agent MVP
```

原因：

- 它能最快验证核心体验。
- 它复用现有工具市场能力。
- 它不依赖向量库和文件系统。
- 它能为后续 RAG、Workflow、高并发架构打基础。

第一阶段建议目标：

```text
用户在 Agent Chat 页面输入自然语言，
系统自动识别意图，
自动调用现有 AI 工具，
并用流式方式返回执行过程和结果。
```
