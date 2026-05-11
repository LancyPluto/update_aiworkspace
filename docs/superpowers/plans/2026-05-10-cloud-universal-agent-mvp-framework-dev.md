# Cloud Universal Agent MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 `user-web`、`backend`、`agent-service` 基础上补齐网页版 GPT 风格 Agent 能力，让用户登录后优先通过自然语言获得推荐、确认并调用平台工具。

**Architecture:** 保留 Spring Boot 作为业务事实源，负责用户、会话、工具、偏好、算力、审计和内部安全；FastAPI `agent-service` 作为智能执行层，使用 LangChain / LangGraph / Deep Agents 能力编排意图识别、工具推荐、确认节点和执行节点；现有 Vue `user-web` 作为唯一用户侧网页，不再新增独立 `agent-web`。旧的 Phase 1.1-1.7 文档只作为历史参考，本文件是 Phase 1 MVP 的单一开发计划。

**Tech Stack:** Vue 3 + Vite + Pinia + Tailwind, Spring Boot 3 + MyBatis-Plus + Redis + MySQL/H2, FastAPI + LangChain + LangGraph + Deep Agents-compatible design, SSE, existing HMAC internal signature.

---

## 0. Product Decision

本轮不再按原计划新建 `agent-web/`。Agent 能力直接进入现有用户端：

```text
/login 登录成功
  -> /agent
  -> 类 GPT / 豆包网页版首页
```

用户第一屏看到聊天入口，而不是先进入工具超市。工具超市仍保留为侧边栏入口，定位为“能力库 / 手动模式”。

Agent 首次判断需要调用某个工具时，必须先展示确认卡片。用户确认后可勾选：

```text
以后类似需求自动调用此工具
```

该偏好按 `user_id + tool_code` 存在后端，跨浏览器、跨设备生效。不同工具独立记忆。

---

## 1. Existing State

当前已存在：

- `user-web`：Vue 用户端，已有登录页、`AppShell`、路由守卫、Pinia token、工具超市、任务页、算力接口。
- `backend`：已有 Agent 包、Agent 表、会话 / run / event / tool call API、OpenAPI 路径、AgentApiTest。
- `agent-service`：已有 FastAPI 骨架、health、execute endpoint、BackendClient、ModelClient、intent router、universal graph、测试。
- `deploy/docker-compose.yml`：已有 `agent-service`，没有 `agent-web`，后续也不需要新增。

主要缺口：

- 登录成功仍跳转工具超市，不是 `/agent`。
- `user-web` 没有 Agent 首页 / 聊天页 / 确认卡片 / run event 消费。
- Spring Boot 创建 Agent run 后尚未稳定触发 `agent-service` 执行。
- Agent 工具调用偏好未落库。
- `agent-service` 的工具确认、自动调用偏好、LangGraph 确认节点还不完整。
- SSE、Agent 算力结算、端到端验收未完成。

---

## 2. Target User Flow

```text
用户登录
  -> 进入 /agent
  -> 输入“帮我给护肤套装写一篇小红书种草文案”
  -> Agent 分析意图
  -> 返回工具确认卡片
  -> 用户确认，并可勾选以后自动调用该工具
  -> Agent 调用工具
  -> 聊天中展示执行过程和结果
  -> 结果、事件、工具调用、算力流水落库
```

如果该用户已对 `xiaohongshu_copywriting` 开启自动调用：

```text
用户输入类似需求
  -> Agent 仍展示识别和执行过程
  -> 不再弹首次确认卡
  -> 直接调用该工具
```

用户必须能在后续设置中关闭单个工具的自动调用偏好。

---

## 3. Phase 1.1: Backend Agent Closure

**Goal:** 让 Spring Boot Agent 模块成为可用业务闭环，补齐工具偏好和触发 `agent-service`。

**Files:**

- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentSessionController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/config/AppProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/client/AgentServiceClient.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentToolPreference.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentToolPreferenceMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentToolPreferenceService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolPreferenceServiceImpl.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentToolPreferenceController.java`
- Create: `sql/006_agent_tool_preferences.sql`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentApiTest.java`

**Steps:**

- [ ] Add `agent_tool_preferences` table:

```sql
CREATE TABLE IF NOT EXISTS agent_tool_preferences (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  auto_call_enabled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_tool_pref_user_tool (user_id, tool_code),
  KEY idx_agent_tool_pref_user (user_id)
);
```

- [ ] Mirror the table in `schema-test.sql`.
- [ ] Add preference APIs:

```text
GET /api/v1/agent/tool-preferences
PUT /api/v1/agent/tool-preferences/{toolCode}
```

Request:

```json
{ "autoCallEnabled": true }
```

- [ ] Add `AgentServiceClient.executeRun(runId)` using the existing internal HMAC signing pattern.
- [ ] In `sendMessage`, after run creation and `run.started`, call `agent-service` asynchronously or in a short background executor. If the call fails, append `run.failed` and mark the run failed with a user-readable message.
- [ ] Add tests for:
  - preference create/update/read
  - run creation triggers an Agent Service execution attempt
  - user cannot read or update another user's preferences

**Verification:**

```powershell
mvn -f backend/pom.xml test -Dtest=AgentApiTest
mvn -f backend/pom.xml test -Dtest=OpenApiContractTest
```

---

## 4. Phase 1.2: Agent Service With LangGraph / LangChain

**Goal:** 用框架承担 Agent 编排，而不是手写复杂 Agent 内核。

**Files:**

- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Modify: `agent-service/app/core/intent_router.py`
- Modify: `agent-service/app/core/schemas.py`
- Modify: `agent-service/app/tools/registry.py`
- Modify: `agent-service/app/tools/backend_tool.py`
- Create: `agent-service/app/tools/confirmation.py`
- Create: `agent-service/app/tools/argument_extractor.py`
- Create: `agent-service/app/security/prompt_guard.py`
- Test: `agent-service/tests/test_universal_graph.py`
- Test: `agent-service/tests/test_intent_router.py`
- Test: `agent-service/tests/test_tool_registry.py`

**Steps:**

- [ ] Keep FastAPI as internal service only. Browser never calls `agent-service` directly.
- [ ] Use LangGraph for the primary graph:

```text
load_context
  -> classify_intent
  -> select_tool
  -> check_tool_preference
  -> needs_confirmation OR execute_tool
  -> synthesize_answer
  -> complete_run
```

- [ ] Use LangChain model/message abstractions for model calls and tool representation.
- [ ] Keep Deep Agents as a compatibility direction: do not block MVP on Deep Agents-specific APIs, but structure code so later planner/sub-agent/memory enhancements can fit behind the graph runtime.
- [ ] Add `needs_confirmation` result when selected tool has no user auto-call preference.
- [ ] Emit events:

```text
intent.detected
tool.selected
tool.confirmation_required
tool.started
tool.finished
message.delta
message.completed
run.completed
run.failed
```

- [ ] If user confirms through backend, continue the same run or create a follow-up run event that resumes execution.

**Verification:**

```powershell
cd agent-service
pytest -q
```

---

## 5. Phase 1.3: Existing user-web GPT-Style Agent Page

**Goal:** 在现有 Vue `user-web` 中新增网页版 GPT 风格 Agent 首页。

**Files:**

- Modify: `user-web/src/router/index.ts`
- Modify: `user-web/src/router/userRoutes.ts`
- Modify: `user-web/src/pages/Login/Page.vue`
- Modify: `user-web/src/components/AppShell.vue`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/index.ts`
- Create: `user-web/src/api/agentApi.ts`
- Create: `user-web/src/pages/AgentHome/Page.vue`
- Create: `user-web/src/components/Agent/AgentShell.vue`
- Create: `user-web/src/components/Agent/SessionSidebar.vue`
- Create: `user-web/src/components/Agent/MessageList.vue`
- Create: `user-web/src/components/Agent/MessageItem.vue`
- Create: `user-web/src/components/Agent/Composer.vue`
- Create: `user-web/src/components/Agent/RunEventList.vue`
- Create: `user-web/src/components/Agent/ToolConfirmationCard.vue`
- Create: `user-web/src/components/Agent/ToolCallCard.vue`

**Steps:**

- [ ] Add `/agent` route with `requiresAuth: true`.
- [ ] Change login success redirect to `/agent`.
- [ ] Add sidebar item:

```text
AI 助手
```

- [ ] Keep existing entries:

```text
AI 工具超市
我的任务
会员与算力
```

- [ ] Build Agent first screen:
  - left session list
  - center greeting
  - prompt suggestions
  - bottom composer
- [ ] Add API wrappers:

```ts
createAgentSession()
listAgentSessions()
listAgentMessages()
sendAgentMessage()
listAgentRunEvents()
confirmAgentToolCall()
updateAgentToolPreference()
```

- [ ] First implementation can poll `/events` every 1s. SSE replaces polling in Phase 1.5.
- [ ] Render `tool.confirmation_required` as a confirmation card with:
  - recommended tool
  - reason
  - arguments preview
  - estimated credits
  - confirm button
  - edit params button
  - checkbox for auto-call this tool later

**Verification:**

```powershell
cd user-web
npx vue-tsc --noEmit
npm run build
```

---

## 6. Phase 1.4: Tool Recommendation And Controlled Execution

**Goal:** Agent 能推荐、确认并受控调用现有工具。

**Files:**

- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolDescriptorServiceImpl.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentToolExecutionService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolExecutionServiceImpl.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentToolController.java`
- Modify: `agent-service/app/tools/registry.py`
- Modify: `agent-service/app/tools/backend_tool.py`
- Modify: `agent-service/app/tools/argument_extractor.py`
- Modify: `user-web/src/components/Agent/ToolConfirmationCard.vue`
- Modify: `user-web/src/components/Agent/ToolCallCard.vue`

**Steps:**

- [ ] Initial allowlist:

```text
xiaohongshu_copywriting
moments_copywriting_generator
product_title_optimizer
wechat_longform_generator
```

- [ ] Backend tool descriptors include:

```text
toolCode
toolName
description
creditCost
inputSchema
autoCallable
requiresConfirmation
userAutoCallEnabled
agentHints
```

- [ ] Convert existing tool fields into JSON Schema.
- [ ] Agent Service selects tools only from backend descriptors.
- [ ] Missing required fields produce a clarification question, not a bad tool call.
- [ ] Confirmed tool call creates `agent_tool_calls` and emits tool events.
- [ ] Auto-call only applies when `userAutoCallEnabled=true` for that tool.

**Verification:**

```powershell
mvn -f backend/pom.xml test -Dtest=AgentApiTest
cd agent-service
pytest tests/test_tool_registry.py tests/test_universal_graph.py -q
```

---

## 7. Phase 1.5: SSE Streaming

**Goal:** 用 SSE 替换正常路径下的 1 秒轮询。

**Files:**

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunSseController.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunEventStreamService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Create: `user-web/src/api/agentStream.ts`
- Modify: `user-web/src/components/Agent/AgentShell.vue`
- Modify: `user-web/src/components/Agent/RunEventList.vue`

**Steps:**

- [ ] Add:

```text
GET /api/v1/agent/runs/{runId}/stream
```

- [ ] Use JWT auth and run ownership checks.
- [ ] Replay missed events using `afterEventId`.
- [ ] Continue to support REST event polling as fallback.
- [ ] Frontend uses fetch-based SSE so Authorization header is available.
- [ ] Hide heartbeat events from timeline.

**Verification:**

```powershell
mvn -f backend/pom.xml test -Dtest=AgentApiTest
cd user-web
npx vue-tsc --noEmit
```

---

## 8. Phase 1.6: Credits, Limits, And Safety

**Goal:** 让 Agent 能力可控、可计费、可审计。

**Files:**

- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/CreditService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/impl/CreditServiceImpl.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentCreditService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentCreditServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/RedisAgentRateLimitService.java`
- Modify: `agent-service/app/security/prompt_guard.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Modify: `user-web/src/components/Agent/MessageItem.vue`

**Steps:**

- [ ] Add `agent_run_id` to credit logs only if current credit schema cannot reference Agent runs cleanly.
- [ ] Freeze estimated credits when a run starts.
- [ ] Settle consumed credits on success.
- [ ] Release unused credits on failure/cancel.
- [ ] Enforce active run limit and message rate limit through Redis.
- [ ] Refuse prompt-injection attempts that ask for secrets, hidden tools, system prompts, or admin actions.
- [ ] Never place internal tokens, model keys, stack traces, or SQL details into user-visible events.
- [ ] UI maps credit/rate/security errors to short user-facing messages.

**Verification:**

```powershell
mvn -f backend/pom.xml test
cd agent-service
pytest -q
cd ..\user-web
npm run build
```

---

## 9. Phase 1.7: End-To-End Acceptance

**Goal:** 用当前 Docker Compose 和现有网页完成可演示闭环。

**Files:**

- Modify: `deploy/docker-compose.yml`
- Modify: `.env.example`
- Create: `scripts/agent-e2e-smoke.ps1`
- Create: `scripts/agent-smoke-load.ps1`

**Steps:**

- [ ] Ensure Compose starts:

```text
mysql
redis
backend
agent-service
user-web
worker
admin-frontend
```

- [ ] Do not add `agent-web`.
- [ ] Backend env includes:

```text
AGENT_ENABLED=true
AGENT_SERVICE_BASE_URL=http://agent-service:8090
```

- [ ] Smoke script covers:
  - login as `user1`
  - create Agent session
  - send natural-language prompt
  - receive tool confirmation event
  - confirm tool call
  - receive result
  - verify messages persist
- [ ] Browser acceptance:
  - login opens `/agent`
  - GPT-style homepage renders
  - prompt suggestions work
  - confirmation card works
  - auto-call preference can be enabled per tool
  - future same-tool request skips confirmation
  - tool result appears in chat
  - tool superstore and task pages still work

**Verification:**

```powershell
docker compose -f deploy/docker-compose.yml config
mvn -f backend/pom.xml test
cd agent-service
pytest -q
cd ..\user-web
npx vue-tsc --noEmit
npm run build
```

---

## 10. Acceptance Criteria

Phase 1 MVP is done when:

- User login lands on `/agent`.
- Agent page is GPT/豆包-style and uses existing `user-web`.
- Agent can create/list sessions and show message history.
- Agent can analyze user intent and recommend at least two existing tools.
- First-time tool call requires user confirmation.
- User can enable per-tool auto-call preference.
- Per-tool preference is stored in backend by user and tool.
- Subsequent same-tool request can auto-call without confirmation.
- Tool calls are recorded in `agent_tool_calls`.
- Events and final messages are persisted.
- SSE works, with polling fallback.
- Credits and active-run limits are enforced.
- Prompt-injection baseline refusal works.
- Existing tool marketplace, task flow, admin frontend, and worker path do not regress.

---

## 11. Document Policy

This file is the single source of truth for Phase 1 Agent MVP implementation.

Historical files retained for reference only:

```text
docs/superpowers/plans/2026-05-10-phase-1-1-spring-boot-agent-module.md
docs/superpowers/plans/2026-05-10-phase-1-2-fastapi-agent-service.md
docs/superpowers/plans/2026-05-10-phase-1-3-nextjs-agent-chat-frontend.md
docs/superpowers/plans/2026-05-10-phase-1-4-agent-tool-registration-auto-call.md
docs/superpowers/plans/2026-05-10-phase-1-5-sse-run-events.md
docs/superpowers/plans/2026-05-10-phase-1-6-agent-credit-rate-limit-security.md
docs/superpowers/plans/2026-05-10-phase-1-7-e2e-integration-acceptance.md
```

Do not create additional Phase 1 planning documents unless this file becomes too large to maintain. If details change, update this file first.
