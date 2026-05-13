# 当前系统架构图

> 更新日期：2026-05-12
> 范围：当前仓库 `integration/backend-core-team-merge-20260510` 的实际实现现状。

## 一、总体架构

```mermaid
flowchart LR
  subgraph Client["客户端"]
    UserWeb["user-web<br/>Vue 3 + Vite<br/>用户端工具/任务/Agent 页面"]
    AdminWeb["admin-frontend<br/>Next.js + React<br/>管理后台"]
  end

  subgraph Backend["业务后端 backend<br/>Spring Boot 3 + Java 17"]
    Auth["认证与用户<br/>JWT / Cookie / AuthContext"]
    Tool["工具市场<br/>分类 / 字段 / Prompt / 工具配置"]
    Task["任务中心<br/>创建 / 状态机 / 结果 / 日志"]
    Credit["算力账户<br/>余额 / 冻结 / 消费流水"]
    AgentBiz["Agent 业务层<br/>会话 / 运行 / 事件 / 文件 / 模型配置"]
    InternalApi["内部 API<br/>HMAC 签名校验"]
  end

  subgraph AgentService["agent-service<br/>FastAPI + LangGraph + LangChain"]
    RuntimeRouter["Runtime Router"]
    LangGraph["LangGraph Runtime<br/>通用 Agent Graph"]
    DeepAgents["Deep Agents Runtime<br/>可选能力"]
    ToolRegistry["Tool Registry<br/>后端工具描述与调用"]
    ModelClient["Model Client<br/>mock / OpenAI compatible / Anthropic compatible"]
    FileRuntime["Workspace Files<br/>文件解析 / chunk 上下文"]
    PromptGuard["Prompt Guard / Budget Guard"]
  end

  subgraph Worker["worker<br/>Python Redis Consumer"]
    QueueConsumer["Redis 队列消费"]
    PromptRenderer["Prompt Renderer"]
    WorkerModel["模型调用"]
    ResultBuilder["结果构造与回写"]
  end

  subgraph Infra["基础设施"]
    MySQL[("MySQL<br/>业务数据")]
    Redis[("Redis<br/>队列 / 限流 / token denylist")]
    FileStore[("本地文件目录<br/>backend/data/agent-files")]
  end

  UserWeb -->|"REST / SSE"| Backend
  AdminWeb -->|"REST"| Backend

  Backend --> Auth
  Backend --> Tool
  Backend --> Task
  Backend --> Credit
  Backend --> AgentBiz
  Backend --> InternalApi

  Auth --> MySQL
  Tool --> MySQL
  Task --> MySQL
  Credit --> MySQL
  AgentBiz --> MySQL
  AgentBiz --> FileStore

  Task -->|"发布传统工具任务"| Redis
  Redis --> QueueConsumer
  QueueConsumer --> PromptRenderer
  PromptRenderer --> WorkerModel
  WorkerModel --> ResultBuilder
  ResultBuilder -->|"内部签名回调"| InternalApi

  AgentBiz -->|"内部签名调用"| AgentService
  AgentService --> RuntimeRouter
  RuntimeRouter --> LangGraph
  RuntimeRouter --> DeepAgents
  LangGraph --> ToolRegistry
  LangGraph --> ModelClient
  LangGraph --> FileRuntime
  LangGraph --> PromptGuard
  DeepAgents --> ToolRegistry
  DeepAgents --> ModelClient
  ToolRegistry -->|"内部签名调用工具/事件/结果接口"| InternalApi
  FileRuntime -->|"读取文件上下文"| InternalApi
  PromptGuard -->|"预算/风控结果"| InternalApi
  AgentService --> Redis

  InternalApi --> MySQL
  InternalApi --> Redis
```

## 二、核心运行链路

```mermaid
sequenceDiagram
  autonumber
  participant U as 用户端/管理端
  participant B as Spring Boot backend
  participant R as Redis
  participant W as Python worker
  participant A as FastAPI agent-service
  participant M as Model Provider
  participant DB as MySQL

  U->>B: 登录、浏览工具、创建任务或发送 Agent 消息
  B->>DB: 校验用户、记录任务/会话/算力
  alt 传统工具任务
    B->>R: 发布任务消息
    W->>R: 消费任务
    W->>B: HMAC 获取 execution-context
    W->>M: 调用模型
    W->>B: HMAC 回写 processing/success/failed
    B->>DB: 更新状态、结果和算力流水
  else 通用 Agent 运行
    B->>A: HMAC 触发 Agent run
    A->>B: HMAC 获取上下文、工具描述、文件/记忆
    A->>M: 调用模型或执行 LangGraph/Deep Agents
    A->>B: HMAC 写入事件、工具调用、最终结果
    B->>DB: 更新 Agent run/message/event/artifact
  end
  U->>B: 查询状态、结果、事件流
```

## 三、模块边界

| 模块 | 当前职责 | 不应越界 |
| --- | --- | --- |
| `backend` | 用户、认证、工具、任务、算力、Agent 业务事实源、内部签名与审计 | 不直接执行大模型编排逻辑 |
| `agent-service` | Agent 推理、运行时路由、LangGraph/Deep Agents、工具选择、模型调用、文件/记忆上下文注入 | 不绕过后端直接修改用户算力、任务状态或权限数据 |
| `worker` | 传统工具任务队列消费、Prompt 渲染、模型调用、结果回写 | 不负责 Agent 会话状态机 |
| `user-web` | 用户侧工具、任务、素材、Agent 工作区交互 | 不持有管理员能力 |
| `admin-frontend` | 管理工具、任务、用户、算力、模型配置等后台界面 | 不直接访问数据库或内部签名接口 |

## 四、当前审查结论

截至 2026-05-12，本地严格验证结果如下：

| 模块 | 命令 | 结果 |
| --- | --- | --- |
| `backend` | `mvn test` | 通过，98 tests |
| `agent-service` | `pytest -q` | 通过，86 tests |
| `user-web` | `npm run build` | 通过 |
| `admin-frontend` | `npm run build` | 未通过 |

`admin-frontend` 的阻断点是模型配置页面与 API 客户端/后端契约不一致：页面使用多模型配置 CRUD（如 `fetchAgentModelConfigs`、`createAgentModelConfig`、`deleteAgentModelConfig`、`setDefaultAgentModelConfig`），但当前 `admin-frontend/lib/api/agent-model.ts` 与后端 `AdminAgentModelConfigController` 只提供单模型配置的 `GET / PUT / POST /test`。

因此，当前代码不建议直接合入 `dev`。建议先统一模型配置能力的产品形态：要么前端回退为单配置编辑，要么后端补齐多配置 CRUD、默认配置、保存后测试等接口，并补充对应构建/测试验收。
