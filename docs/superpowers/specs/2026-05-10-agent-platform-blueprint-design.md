# Agent Platform Blueprint Design

> Date: 2026-05-10
> Scope: 通用工作空间型 Agent 平台蓝图
> Status: Draft for review
> Related MVP plan: `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`

## 1. Goal

把当前 AI Tool Market 从“工具市场 + Agent MVP”演进为通用工作空间型 Agent 平台。

目标体验接近 ChatGPT / Claude 的工作空间能力：用户可以围绕一个项目上传文件、持续对话、沉淀上下文、执行长任务、调用平台工具、委派子 Agent，并在后续会话中继续使用已积累的工作区记忆。工具市场仍然重要，但它只是 Agent 可调用能力的一类，不再是唯一入口。

本蓝图覆盖 LangChain、LangGraph、Deep Agents、子 Agent、长期记忆、文件工作区、工具市场扩展、观测、权限边界和实施分期。

## 2. Current Project Baseline

当前项目已经具备较好的 Agent MVP 基础：

- `backend` 是业务事实源，已有 Agent session、message、run、event、tool call、tool preference、file、model config、SSE、算力与限流相关代码。
- `agent-service` 是 FastAPI 智能执行层，已有 LangChain 消息边界、LangGraph universal graph、模型工厂、文件解析、后端回调、测试。
- `user-web` 已有 `/agent` 页面、Agent API wrapper、SSE/polling、工具确认卡片和文件上传入口。
- `deploy/docker-compose.yml` 已包含 `agent-service`。

验证结果：

- `agent-service`: `pytest -q` 通过，44 tests passed。
- `backend`: `AgentApiTest,RedisAgentRateLimitServiceTest,OpenApiContractTest` 通过，18 tests passed。
- `user-web`: `vue-tsc --noEmit` 当前有一个类型错误，位于 `user-web/src/api/client.ts` 的 fetch body 类型。

## 3. Framework Positioning

### 3.1 LangChain

LangChain 作为模型、消息、工具、结构化输出和 provider 适配层。平台代码不直接依赖某个模型厂商的原生 SDK，而是通过 LangChain-compatible chat model 和 message objects 抽象模型调用。

在本项目中，LangChain 应承担：

- chat model provider abstraction。
- system/user/assistant message conversion。
- structured output / JSON argument extraction。
- tool schema metadata 的统一表达。

### 3.2 LangGraph

LangGraph 作为可控状态机 runtime。它适合当前 MVP 中需要强业务边界、可测试、可审计的流程：

- 意图识别。
- 工具选择。
- 人工确认。
- 工具调用。
- 结果合成。
- 算力预算检查。
- run completion / failure。

LangGraph Runtime 是 Phase 1 和 Phase 2 早期的主执行路径。

### 3.3 Deep Agents

Deep Agents 作为复杂工作空间任务 runtime，而不是立刻替换现有 LangGraph MVP。

它适合：

- 多步规划。
- 长任务执行。
- 文件工作区读写。
- 子 Agent 委派。
- 工作区记忆使用。
- human-in-the-loop 中断。

Deep Agents runtime 必须接入平台的权限、审计、工具 allowlist、算力预算和文件沙箱，不能绕过 Spring Boot 后端。

Official references:

- Deep Agents overview: https://docs.langchain.com/oss/python/deepagents/overview
- Deep Agents reference: https://reference.langchain.com/python/deepagents/graph/create_deep_agent/
- LangGraph docs: https://langchain-ai.github.io/langgraph/
- LangChain tools docs: https://python.langchain.com/docs/concepts/tools/

## 4. Target Architecture

```text
Browser user-web
  -> Spring Boot backend
     -> auth, users, workspace, sessions, files, tools, credits, audit, SSE
     -> signed internal API
        -> FastAPI agent-service
           -> Runtime Router
              -> LangGraphRuntimeEngine
              -> DeepAgentsRuntimeEngine
           -> LangChain model/tool adapters
           -> workspace memory access through backend APIs
           -> tool execution through backend APIs
```

Hard boundary:

- Browser never calls `agent-service` directly.
- `agent-service` never reads or writes business database tables directly.
- Spring Boot owns permissions, persistence, credits, audit, and tool availability.
- Python runtime owns reasoning, planning, model orchestration, and internal graph execution.

## 5. Runtime Design

### 5.1 Runtime Router

Add an explicit runtime abstraction in `agent-service`:

```python
class AgentRuntimeEngine:
    async def execute_run(self, context: RunContext) -> RunResult: ...
```

Implementations:

- `LangGraphRuntimeEngine`: deterministic, policy-heavy, testable MVP flow.
- `DeepAgentsRuntimeEngine`: workspace-oriented planning, file work, sub-agent delegation.

Routing policy:

- Simple chat, first-time tool recommendation, confirmation, and controlled tool call use LangGraph.
- Long tasks, multi-file reasoning, multi-step project work, or explicit "plan and execute" requests use Deep Agents after it is enabled.
- If Deep Agents fails policy checks, downgrade to LangGraph clarification or refusal.

### 5.2 LangGraph Runtime

Primary nodes:

```text
load_context
  -> inspect_prompt_safety
  -> classify_intent
  -> select_tool_or_workspace_action
  -> check_budget
  -> check_confirmation
  -> execute_tool_or_answer
  -> synthesize_answer
  -> persist_memory_candidates
  -> complete_run
```

This keeps the current `UniversalAgentGraph` but evolves it into smaller node modules.

### 5.3 Deep Agents Runtime

Deep Agents should be enabled behind a feature flag:

```text
AGENT_DEEP_AGENTS_ENABLED=false
```

Initial Deep Agents capabilities:

- read workspace file summaries and selected file chunks.
- create a task plan.
- delegate to subagents for bounded research/synthesis tasks.
- write generated artifacts into a virtual workspace file layer.
- propose memory candidates.
- call only platform-approved tools.

Initial disabled capabilities:

- arbitrary shell execution.
- external browser automation.
- direct network crawling.
- direct filesystem access outside the virtual workspace.
- tools with irreversible side effects.

## 6. Workspace Model

Introduce workspace as the top-level user mental model.

Suggested entities:

```text
agent_workspaces
agent_workspace_members
agent_workspace_files
agent_workspace_memory_items
agent_workspace_artifacts
agent_workspace_tasks
agent_workspace_tool_permissions
```

Relationship:

```text
workspace
  -> sessions
  -> runs
  -> messages
  -> files
  -> memory items
  -> artifacts
  -> tool permissions
```

Existing session APIs can remain. A session belongs to a workspace. For backward compatibility, creating a session without a workspace creates or uses a default personal workspace.

## 7. Memory Strategy

Chosen strategy:

1. 工作区记忆优先。
2. 用户偏好轻量内建。
3. 业务知识记忆作为可插拔知识库阶段。

### 7.1 Workspace Memory

Workspace memory stores project-specific context:

- project goal。
- uploaded file summaries。
- key decisions。
- task plans。
- accepted constraints。
- generated artifacts。
- reusable context extracted from completed runs。

Memory lifecycle:

```text
run completes
  -> agent proposes memory candidates
  -> policy filters sensitive/low-value entries
  -> optional user confirmation
  -> backend persists approved memory
```

Memory retrieval:

- use recent run context first。
- retrieve workspace memory by relevance。
- retrieve file chunks only when the current request needs file context。
- show citations when using files or persistent memory.

### 7.2 User Preference Memory

User preference memory should stay small and explicit:

- preferred tone。
- preferred language。
- common platforms。
- default output format。
- per-tool auto-call preferences。

Existing `agent_tool_preferences` is the first preference memory table. Future user-level preferences should remain editable and deletable.

### 7.3 Business Knowledge Memory

Business knowledge is a later pluggable knowledge-base capability:

- enterprise SOP。
- industry best practices。
- tool usage recipes。
- brand guidelines。
- shared team knowledge。

It must have independent permissions, source versioning, and citation requirements.

## 8. File Workspace

Files are first-class workspace resources.

Layers:

- uploaded files: original user files, parsed text, chunks。
- generated artifacts: markdown, JSON, documents, exports。
- virtual working files: Deep Agents scratchpad or project files。

Rules:

- `agent-service` receives file context only through backend internal APIs。
- Deep Agents works on a virtual file API backed by Spring Boot, not host filesystem paths。
- File writes require audit events。
- Large files are chunked and retrieved selectively。
- Sensitive file classes can require user confirmation before model access。

Suggested event types:

```text
file.read
file.chunk_retrieved
file.generated
file.updated
artifact.created
```

## 9. Sub-Agent Model

Sub Agents are specialized assistants created inside a run, not independent platform users.

Initial sub-agent roles:

- `researcher`: summarize files and gather context from allowed knowledge sources。
- `writer`: produce polished content artifacts。
- `critic`: review output against requirements。
- `tool_operator`: prepare tool arguments and inspect results。

Rules:

- Parent run owns all sub-agent actions。
- Each sub-agent has a bounded prompt, tool allowlist, file scope, and budget。
- Sub-agent outputs are logged as run events or internal traces。
- User-visible summaries should not expose hidden prompts or internal chain details。

## 10. Tool Marketplace Integration

The tool marketplace becomes one tool provider.

Tool descriptor contract should include:

```text
toolCode
toolName
description
category
inputSchema
estimatedCreditCost
autoCallable
requiresConfirmation
sideEffectLevel
permissions
agentHints
version
```

Tool side-effect levels:

```text
READ_ONLY
GENERATE_CONTENT
MUTATE_WORKSPACE
EXTERNAL_ACTION
ACCOUNT_OR_PAYMENT
ADMIN_ACTION
```

Policy:

- `READ_ONLY` and `GENERATE_CONTENT` can be auto-callable if user preference allows。
- `MUTATE_WORKSPACE` requires clear preview and undo path。
- `EXTERNAL_ACTION` requires human confirmation。
- `ACCOUNT_OR_PAYMENT` and `ADMIN_ACTION` are denied for autonomous Agent execution in early phases。

The model cannot invent tools. It can only call descriptors returned by Spring Boot for the current user and workspace.

## 11. Permissions And Safety

### 11.1 Permission Layers

```text
user auth
  -> workspace membership
  -> run ownership
  -> file access
  -> memory access
  -> tool permission
  -> side-effect policy
  -> budget policy
```

### 11.2 Prompt And Tool Safety

Baseline refusals:

- reveal system prompts。
- reveal internal tokens。
- bypass permissions。
- access tools not in descriptor list。
- perform admin/payment/account actions。
- exfiltrate files or memory beyond workspace scope。

### 11.3 Human-In-The-Loop

Interrupt points:

- first-time tool call。
- sensitive file access。
- external action。
- memory persistence。
- workspace artifact overwrite。
- high-cost run continuation。

The frontend should render these as confirmation cards with explicit action, preview, cost, and scope.

## 12. Credits, Limits, And Long Tasks

Spring Boot owns all accounting.

Run budgeting:

```text
estimated run budget
  -> freeze credits
  -> sub-budget per model/tool/subagent/file operation
  -> settle consumed credits on success
  -> release unused credits on failure/cancel
```

Long tasks:

- use persisted run state。
- expose run status through SSE and REST polling。
- allow cancel。
- support resume from interruption。
- store checkpoints for Deep Agents / LangGraph where available。

Limits:

- active runs per user。
- messages per minute。
- runs per hour。
- max tool calls per run。
- max model calls per run。
- max sub-agent count。
- max workspace file context tokens。

## 13. Observability And Audit

Required audit trail:

- run started/completed/failed。
- model provider and model name。
- selected runtime。
- selected tools。
- tool arguments preview。
- file reads/writes。
- memory candidates and persisted memory。
- sub-agent starts/completions。
- user confirmations。
- credit freeze/settle/release。
- safety refusals。

Observability targets:

- run latency。
- model call latency。
- token/cost estimate。
- tool success/failure rate。
- confirmation acceptance rate。
- memory retrieval hit rate。
- Deep Agents fallback rate。

Do not expose hidden chain-of-thought. Store concise operational traces and user-safe summaries.

## 14. Data Model Additions

Near-term additions:

```text
agent_workspaces
agent_workspace_members
agent_workspace_memory_items
agent_workspace_artifacts
agent_run_runtime_traces
agent_subagent_runs
agent_memory_candidates
```

Potential future additions:

```text
knowledge_bases
knowledge_documents
knowledge_document_chunks
knowledge_permissions
tool_provider_integrations
tool_permission_policies
```

## 15. API Additions

Public browser APIs through Spring Boot:

```text
GET    /api/v1/agent/workspaces
POST   /api/v1/agent/workspaces
GET    /api/v1/agent/workspaces/{workspaceId}
GET    /api/v1/agent/workspaces/{workspaceId}/memory
PUT    /api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}
DELETE /api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}
GET    /api/v1/agent/workspaces/{workspaceId}/artifacts
GET    /api/v1/agent/runs/{runId}/trace-summary
```

Internal Spring Boot to Agent Service APIs:

```text
GET  /api/internal/v1/agent/runs/{runId}/context
POST /api/internal/v1/agent/runs/{runId}/execute
POST /api/internal/v1/agent/runs/{runId}/confirm-tool
POST /api/internal/v1/agent/workspaces/{workspaceId}/memory/retrieve
POST /api/internal/v1/agent/workspaces/{workspaceId}/artifacts
```

All internal APIs remain signed.

## 16. Frontend Experience

The Agent page should evolve into a workspace surface:

- left: workspace/session navigation。
- center: chat and run status。
- right or drawer: files, artifacts, memory, tool approvals。
- cards: confirmation, tool call, artifact, file citation, memory saved。

Important UX requirements:

- user sees what the Agent is doing at a useful level。
- confirmations show action, scope, cost, and risk。
- files and memory are inspectable and removable。
- long tasks can be cancelled。
- tool marketplace remains available as manual mode。

## 17. Implementation Phases

### Phase A: Stabilize Current MVP

- Fix `user-web/src/api/client.ts` TypeScript body issue。
- Ensure current LangGraph flow passes backend, agent-service, and user-web builds。
- Complete existing tool confirmation and SSE path。
- Add end-to-end smoke script。

### Phase B: Runtime Abstraction

- Introduce `AgentRuntimeEngine` abstraction。
- Move current graph into `LangGraphRuntimeEngine`。
- Add runtime selection field to run context and trace。
- Keep behavior unchanged.

### Phase C: Workspace Memory MVP

- Add workspace entities。
- Attach sessions to workspaces。
- Add workspace memory candidates。
- Add memory retrieval into LangGraph context。
- Add user UI to view/delete memory。

### Phase D: Deep Agents Preview Runtime

- Add optional `deepagents` dependency behind feature flag。
- Implement Deep Agents runtime for read-only workspace tasks。
- Enable virtual file read/write through backend APIs。
- Disable shell/network/external actions。
- Add sub-agent traces and budget controls。

### Phase E: Tool Marketplace Expansion

- Add side-effect levels and tool policies。
- Improve JSON Schema descriptors。
- Add argument extraction and validation。
- Add more tool categories and integration-provider interfaces。

### Phase F: Knowledge Base Plugin

- Add knowledge base tables and permissions。
- Add retrieval APIs。
- Integrate business knowledge as a separate context source。
- Require citations and source visibility。

### Phase G: Production Hardening

- checkpointing and resume。
- advanced rate limits。
- observability dashboards。
- audit export。
- workspace sharing。
- enterprise policies。

## 18. Testing Strategy

Backend:

- workspace ownership。
- memory CRUD and permission checks。
- internal signed APIs。
- credit accounting。
- SSE replay。
- tool policy enforcement。

Agent Service:

- runtime routing。
- LangGraph regression tests。
- Deep Agents feature-flag tests。
- virtual file permissions。
- memory retrieval。
- sub-agent budget limits。

Frontend:

- typecheck/build。
- workspace navigation。
- confirmation cards。
- memory management。
- long-task cancellation。

End-to-end:

- login -> workspace -> upload file -> ask multi-step question -> Agent uses file -> creates artifact -> proposes memory -> completes run。
- tool confirmation -> auto-call preference -> repeated tool request skips confirmation。
- cancel long run -> credits released。

## 19. Risks And Decisions

### Key Risks

- Deep Agents can expand capability faster than permission controls if introduced too early。
- workspace memory can accumulate low-quality or sensitive data。
- long tasks can create unclear cost exposure。
- direct file or shell access would be unsafe without a strong sandbox。
- business knowledge RAG requires mature source permissions and citations。

### Decisions

- Keep Spring Boot as business fact source。
- Keep Browser isolated from `agent-service`。
- Keep LangGraph as stable MVP runtime。
- Add Deep Agents as optional workspace runtime, not a replacement。
- Prioritize workspace memory before business knowledge memory。
- Treat all memory writes as auditable and reversible。

## 20. Approval Checklist

This blueprint is approved when the team agrees:

- the platform target is a general workspace Agent。
- LangGraph remains the stable MVP runtime。
- Deep Agents is introduced behind a feature flag。
- workspace memory is the first long-term memory priority。
- user preference memory remains lightweight。
- business knowledge memory is a later plugin。
- Spring Boot remains the source of truth for permissions, credits, audit, and persistence。
