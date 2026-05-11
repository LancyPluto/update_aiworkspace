# Phase 1.1 Spring Boot Agent Business Module Implementation Plan

> Superseded: Phase 1 implementation should now follow `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`. This file is retained only as historical detail.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Spring Boot business module that owns Agent sessions, messages, runs, events, tool calls, authorization, credit reservation, and the internal contract used by the FastAPI Agent Service.

**Architecture:** Spring Boot remains the business source of truth. The new `agent` package follows the existing Controller -> Service -> ServiceImpl -> Mapper pattern and uses MyBatis-Plus plus annotation SQL, matching the current `task`, `tool`, `credit`, and `user` modules.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis-Plus, MySQL/H2, Redis, existing JWT auth, existing internal HMAC signature verifier, existing `ApiResponse<T>` and `PageResponse<T>`.

---

## 1. Scope

Phase 1.1 only builds the Spring Boot side of the Agent MVP.

It includes:

- Agent database schema.
- Public user Agent APIs.
- Internal Agent APIs for FastAPI Agent Service.
- Agent session persistence.
- Agent message persistence.
- Agent run lifecycle.
- Agent event persistence.
- Agent tool call persistence.
- Agent tool descriptor export.
- Basic active-run and message-rate limits.
- Credit reservation and settlement design hooks.
- Tests for access control, ownership, status changes, and internal signatures.

It does not include:

- FastAPI Agent Service implementation.
- LangGraph runtime.
- LangChain tool execution.
- Next.js Agent UI.
- SSE implementation details beyond Spring Boot API contract.
- RAG, file upload, vector database, RabbitMQ, Celery, MinIO, Kubernetes.

---

## 2. Current Backend Context

The current backend already has useful foundations:

- `AuthInterceptor` protects `/api/**` and validates `/api/internal/v1/**` with HMAC signature.
- `AuthContext` exposes current user id and user type.
- `ApiResponse<T>` wraps all API responses.
- `PageResponse<T>` provides pagination helpers.
- `TaskQueuePublisher` publishes Redis list messages for existing AI tasks.
- `CreditService` owns credit reservation, release, and consumption for tasks.
- `ToolService` and `ToolMapper` expose tool definitions and field schemas.
- Tests use `@SpringBootTest`, MockMvc, and H2.

Phase 1.1 should reuse these patterns.

---

## 3. Package Structure

Create:

```text
backend/src/main/java/com/aiminilab/aitoolmarket/agent/
  controller/
    AgentSessionController.java
    AgentRunController.java
    InternalAgentController.java
  service/
    AgentSessionService.java
    AgentRunService.java
    AgentToolDescriptorService.java
    AgentRateLimitService.java
  service/impl/
    AgentSessionServiceImpl.java
    AgentRunServiceImpl.java
    AgentToolDescriptorServiceImpl.java
    RedisAgentRateLimitService.java
  mapper/
    AgentSessionMapper.java
    AgentMessageMapper.java
    AgentRunMapper.java
    AgentRunEventMapper.java
    AgentToolCallMapper.java
  entity/
    AgentSession.java
    AgentMessage.java
    AgentRun.java
    AgentRunEvent.java
    AgentToolCall.java
  dto/
    CreateAgentSessionRequest.java
    AgentSessionResponse.java
    CreateAgentMessageRequest.java
    CreateAgentMessageResponse.java
    AgentMessageResponse.java
    AgentRunResponse.java
    AgentRunEventResponse.java
    CreateAgentRunEventRequest.java
    CompleteAgentRunRequest.java
    FailAgentRunRequest.java
    CreateAgentToolCallRequest.java
    CompleteAgentToolCallRequest.java
    AgentToolDescriptorResponse.java
    AgentToolFieldDescriptorResponse.java
```

Tests:

```text
backend/src/test/java/com/aiminilab/aitoolmarket/agent/
  AgentSessionApiTest.java
  AgentRunApiTest.java
  InternalAgentApiTest.java
  AgentSecurityApiTest.java
```

---

## 4. Database Migration

Add a new SQL migration:

```text
sql/005_agent_module.sql
```

For tests, update:

```text
backend/src/test/resources/schema-test.sql
```

### 4.1 agent_sessions

```sql
CREATE TABLE IF NOT EXISTS agent_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_agent_sessions_user_updated (user_id, updated_at),
  INDEX idx_agent_sessions_status (status)
);
```

Status values:

```text
ACTIVE
ARCHIVED
DELETED
```

### 4.2 agent_messages

```sql
CREATE TABLE IF NOT EXISTS agent_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content_text MEDIUMTEXT NOT NULL,
  content_json JSON NULL,
  run_id BIGINT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_agent_messages_session_id (session_id, id),
  INDEX idx_agent_messages_user_id (user_id, id),
  INDEX idx_agent_messages_run_id (run_id)
);
```

Role values:

```text
USER
ASSISTANT
SYSTEM
TOOL
```

### 4.3 agent_runs

```sql
CREATE TABLE IF NOT EXISTS agent_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  intent VARCHAR(64) NULL,
  model_provider_code VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  estimated_credits INT NOT NULL DEFAULT 0,
  consumed_credits INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_agent_runs_user_created (user_id, created_at),
  INDEX idx_agent_runs_session_created (session_id, created_at),
  INDEX idx_agent_runs_status (status)
);
```

Status values:

```text
CREATED
RUNNING
WAITING_USER_CONFIRMATION
SUCCESS
FAILED
TIMEOUT
CANCELLED
```

### 4.4 agent_run_events

```sql
CREATE TABLE IF NOT EXISTS agent_run_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_text TEXT NULL,
  event_json JSON NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_agent_run_events_run_id (run_id, id),
  INDEX idx_agent_run_events_user_id (user_id, id)
);
```

Event types:

```text
run.started
intent.detected
tool.selected
tool.started
tool.finished
message.delta
message.completed
run.completed
run.failed
```

### 4.5 agent_tool_calls

```sql
CREATE TABLE IF NOT EXISTS agent_tool_calls (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  arguments_json JSON NOT NULL,
  result_json JSON NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_agent_tool_calls_run_id (run_id),
  INDEX idx_agent_tool_calls_user_id (user_id, id),
  INDEX idx_agent_tool_calls_tool_code (tool_code)
);
```

Status values:

```text
CREATED
RUNNING
SUCCESS
FAILED
SKIPPED
```

---

## 5. Entity Design

Entities should follow existing Java bean style used by current modules.

### 5.1 AgentSession

Fields:

```text
Long id
Long userId
String title
String status
LocalDateTime createdAt
LocalDateTime updatedAt
```

### 5.2 AgentMessage

Fields:

```text
Long id
Long sessionId
Long userId
String role
String contentText
String contentJson
Long runId
LocalDateTime createdAt
```

### 5.3 AgentRun

Fields:

```text
Long id
Long sessionId
Long userId
String status
String intent
String modelProviderCode
String modelName
Integer estimatedCredits
Integer consumedCredits
String errorCode
String errorMessage
LocalDateTime startedAt
LocalDateTime finishedAt
LocalDateTime createdAt
LocalDateTime updatedAt
```

### 5.4 AgentRunEvent

Fields:

```text
Long id
Long runId
Long userId
String eventType
String eventText
String eventJson
LocalDateTime createdAt
```

### 5.5 AgentToolCall

Fields:

```text
Long id
Long runId
Long userId
String toolCode
String status
String argumentsJson
String resultJson
String errorCode
String errorMessage
LocalDateTime startedAt
LocalDateTime finishedAt
LocalDateTime createdAt
```

---

## 6. DTO Design

### 6.1 CreateAgentSessionRequest

```java
public record CreateAgentSessionRequest(
        String title
) {
}
```

Rules:

- `title` can be blank.
- Blank title becomes `新对话`.
- Maximum title length: 120.

### 6.2 AgentSessionResponse

```java
public record AgentSessionResponse(
        Long id,
        String title,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

### 6.3 CreateAgentMessageRequest

```java
public record CreateAgentMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容过长")
        String content,
        String clientRequestId
) {
}
```

`clientRequestId` is reserved for idempotency. Phase 1.1 should persist logic only if implementation cost stays low. If not, keep field but do not enforce idempotency until Phase 1.6.

### 6.4 CreateAgentMessageResponse

```java
public record CreateAgentMessageResponse(
        Long sessionId,
        Long messageId,
        Long runId,
        String runStatus
) {
}
```

### 6.5 AgentRunResponse

```java
public record AgentRunResponse(
        Long id,
        Long sessionId,
        String status,
        String intent,
        String modelProviderCode,
        String modelName,
        Integer estimatedCredits,
        Integer consumedCredits,
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

### 6.6 CreateAgentRunEventRequest

```java
public record CreateAgentRunEventRequest(
        @NotBlank String eventType,
        String eventText,
        Object eventJson
) {
}
```

### 6.7 CompleteAgentRunRequest

```java
public record CompleteAgentRunRequest(
        @NotBlank String finalAnswer,
        String intent,
        String modelProviderCode,
        String modelName,
        Integer consumedCredits
) {
}
```

### 6.8 FailAgentRunRequest

```java
public record FailAgentRunRequest(
        @NotBlank String errorCode,
        @NotBlank String errorMessage
) {
}
```

### 6.9 AgentToolDescriptorResponse

```java
public record AgentToolDescriptorResponse(
        String toolCode,
        String name,
        String description,
        Integer creditCost,
        Object inputSchema,
        boolean autoCallable
) {
}
```

---

## 7. Public User APIs

All public APIs require login and use `AuthContext.get().userId()`.

### 7.1 Create Session

```text
POST /api/v1/agent/sessions
```

Request:

```json
{
  "title": "新品推广"
}
```

Response:

```json
{
  "code": "SUCCESS",
  "data": {
    "id": 10001,
    "title": "新品推广",
    "status": "ACTIVE",
    "createdAt": "2026-05-10T10:00:00",
    "updatedAt": "2026-05-10T10:00:00"
  }
}
```

### 7.2 List Sessions

```text
GET /api/v1/agent/sessions?pageNo=1&pageSize=20
```

Return `PageResponse<AgentSessionResponse>`.

Only return current user's non-deleted sessions.

### 7.3 Get Session

```text
GET /api/v1/agent/sessions/{sessionId}
```

Only current owner can read.

### 7.4 List Messages

```text
GET /api/v1/agent/sessions/{sessionId}/messages?pageNo=1&pageSize=50
```

Return current session messages ordered by `id ASC`.

### 7.5 Send Message

```text
POST /api/v1/agent/sessions/{sessionId}/messages
```

Request:

```json
{
  "content": "帮我写一篇小红书种草文案",
  "clientRequestId": "optional-client-id"
}
```

Behavior:

1. Verify session owner.
2. Check session is `ACTIVE`.
3. Apply rate limit.
4. Check active run limit.
5. Reserve estimated credits.
6. Insert user message.
7. Insert agent run with `CREATED`.
8. Mark run `RUNNING`.
9. Trigger Agent Service execution in a follow-up implementation.
10. Return message id and run id.

Phase 1.1 may stop at creating the run and emitting a `run.started` event. Actual FastAPI call is implemented in Phase 1.2 / 1.5.

### 7.6 Get Run

```text
GET /api/v1/agent/runs/{runId}
```

Only current owner can read.

### 7.7 Cancel Run

```text
POST /api/v1/agent/runs/{runId}/cancel
```

Allowed statuses:

```text
CREATED
RUNNING
WAITING_USER_CONFIRMATION
```

Behavior:

- Mark run `CANCELLED`.
- Release unused reserved credits.
- Insert `run.failed` or `run.cancelled` event.

### 7.8 List Run Events

```text
GET /api/v1/agent/runs/{runId}/events?afterEventId=0
```

Return events ordered by id ASC.

This API supports frontend recovery after SSE disconnect.

---

## 8. Internal Agent APIs

Internal APIs are called by FastAPI Agent Service and protected by current `/api/internal/v1/**` HMAC signature.

### 8.1 Get Run Context

```text
GET /api/internal/v1/agent/runs/{runId}/context
```

Response:

```json
{
  "runId": 10001,
  "sessionId": 20001,
  "userId": 30001,
  "message": "帮我写一篇小红书种草文案",
  "history": [
    {
      "role": "USER",
      "content": "上一条用户消息"
    },
    {
      "role": "ASSISTANT",
      "content": "上一条助手消息"
    }
  ],
  "availableTools": [
    {
      "toolCode": "xiaohongshu_copywriting",
      "name": "AI 小红书文案生成器",
      "description": "根据产品和目标人群生成小红书文案",
      "creditCost": 5,
      "inputSchema": {},
      "autoCallable": true
    }
  ],
  "creditBudget": 20
}
```

### 8.2 Append Run Event

```text
POST /api/internal/v1/agent/runs/{runId}/events
```

Request:

```json
{
  "eventType": "intent.detected",
  "eventText": "已识别为工具调用任务",
  "eventJson": {
    "intent": "tool_use",
    "confidence": 0.91
  }
}
```

Behavior:

- Verify run exists.
- Insert event with run owner user id.

### 8.3 Create Tool Call

```text
POST /api/internal/v1/agent/runs/{runId}/tool-calls
```

Request:

```json
{
  "toolCode": "xiaohongshu_copywriting",
  "argumentsJson": {
    "productName": "护肤套装",
    "targetCustomer": "25-35 岁女性",
    "style": "自然种草"
  }
}
```

Behavior:

- Verify run exists.
- Verify tool is available and online.
- Insert `agent_tool_calls` row with `CREATED` or `RUNNING`.
- Insert `tool.started` event.

### 8.4 Complete Tool Call

```text
POST /api/internal/v1/agent/tool-calls/{toolCallId}/complete
```

Request:

```json
{
  "resultJson": {
    "content": "生成结果"
  }
}
```

Behavior:

- Mark tool call `SUCCESS`.
- Insert `tool.finished` event.

### 8.5 Fail Tool Call

```text
POST /api/internal/v1/agent/tool-calls/{toolCallId}/fail
```

Request:

```json
{
  "errorCode": "TOOL_CALL_FAILED",
  "errorMessage": "工具调用失败"
}
```

Behavior:

- Mark tool call `FAILED`.
- Insert `tool.finished` event with error payload.

### 8.6 Complete Run

```text
POST /api/internal/v1/agent/runs/{runId}/complete
```

Request:

```json
{
  "finalAnswer": "这是生成好的文案...",
  "intent": "tool_use",
  "modelProviderCode": "deepseek",
  "modelName": "deepseek-chat",
  "consumedCredits": 6
}
```

Behavior:

- Insert assistant message.
- Mark run `SUCCESS`.
- Set intent, model provider, model name, consumed credits.
- Set finished_at.
- Settle credits.
- Insert `message.completed`.
- Insert `run.completed`.

### 8.7 Fail Run

```text
POST /api/internal/v1/agent/runs/{runId}/fail
```

Request:

```json
{
  "errorCode": "MODEL_CALL_FAILED",
  "errorMessage": "模型调用失败"
}
```

Behavior:

- Mark run `FAILED`.
- Set error fields and finished_at.
- Release unused reserved credits.
- Insert `run.failed`.

---

## 9. Tool Descriptor Export

Agent Service needs tool descriptors, not raw database rows.

`AgentToolDescriptorService` should convert existing online tools into stable descriptors:

```text
toolCode
name
description
creditCost
inputSchema
autoCallable
```

Rules:

- Only `ONLINE` tools can be exported.
- Tools without field schema are excluded or marked not auto-callable.
- First phase can mark seeded text generation tools as `autoCallable = true`.
- Admin-managed future tools can add an explicit `agent_auto_callable` field in a later migration.

Initial auto-callable tool candidates:

```text
xiaohongshu_copywriting
moments_copywriting_generator
product_title_optimizer
wechat_longform_generator
```

---

## 10. Credit Strategy

Phase 1.1 should implement a conservative integration point.

### 10.1 Reservation

When user sends a message:

```text
estimatedCredits = app.agent.default-credit-budget
```

Default:

```text
20
```

The Agent module should call CreditService to reserve/freeze credits if the existing CreditService supports reservation semantics. If current CreditService is task-specific, add an Agent-specific wrapper `AgentCreditService` that initially performs validation and records estimated credits, then defers exact freeze integration to Phase 1.6.

### 10.2 Settlement

When Agent Service completes run:

```text
consumedCredits = request.consumedCredits
```

Rules:

- consumedCredits cannot be negative.
- consumedCredits cannot exceed estimatedCredits unless admin config allows overage.
- success settles consumed credits.
- failure releases unused reservation.
- cancellation releases unused reservation.

If existing credit logs require task id, Phase 1.1 should document the needed schema extension and use no-op settlement in tests. Phase 1.6 will harden credit accounting.

---

## 11. Rate Limit Strategy

Create `AgentRateLimitService`.

Redis keys:

```text
agent:rate:user:{userId}:minute
agent:active_runs:user:{userId}
agent:run_lock:{runId}
```

Methods:

```java
void checkMessageRate(Long userId);
void checkActiveRunLimit(Long userId);
void incrementActiveRun(Long userId, Long runId);
void decrementActiveRun(Long userId, Long runId);
```

Default config:

```yaml
app:
  agent:
    max-active-runs-per-user: 1
    max-messages-per-minute: 10
    default-credit-budget: 20
```

If Redis is unavailable:

- In local/dev: allow request and log warning.
- In production: reject with `SYSTEM_ERROR` or a specific rate-limit error after config is added.

---

## 12. Configuration

Add to `AppProperties`:

```text
agent.enabled
agent.serviceBaseUrl
agent.maxActiveRunsPerUser
agent.maxMessagesPerMinute
agent.defaultCreditBudget
```

Add to `application.yml`:

```yaml
app:
  agent:
    enabled: ${AGENT_ENABLED:true}
    service-base-url: ${AGENT_SERVICE_BASE_URL:http://127.0.0.1:8090}
    max-active-runs-per-user: ${AGENT_MAX_ACTIVE_RUNS_PER_USER:1}
    max-messages-per-minute: ${AGENT_MAX_MESSAGES_PER_MINUTE:10}
    default-credit-budget: ${AGENT_DEFAULT_CREDIT_BUDGET:20}
```

Production validator should eventually require non-local Agent Service URL if `agent.enabled=true` and production mode is enabled. Phase 1.1 can leave this as a warning-level check if production deployment is not part of this stage.

---

## 13. Service Design

### 13.1 AgentSessionService

Methods:

```java
AgentSessionResponse create(Long userId, CreateAgentSessionRequest request);
PageResponse<AgentSessionResponse> list(Long userId, Integer pageNo, Integer pageSize);
AgentSessionResponse detail(Long userId, Long sessionId);
PageResponse<AgentMessageResponse> messages(Long userId, Long sessionId, Integer pageNo, Integer pageSize);
```

Ownership check:

- Every session query must include `user_id`.
- Missing session should return business error `NOT_FOUND` or existing equivalent.

### 13.2 AgentRunService

Methods:

```java
CreateAgentMessageResponse sendMessage(Long userId, Long sessionId, CreateAgentMessageRequest request);
AgentRunResponse detail(Long userId, Long runId);
AgentRunResponse cancel(Long userId, Long runId);
PageResponse<AgentRunEventResponse> events(Long userId, Long runId, Long afterEventId, Integer pageSize);
InternalAgentRunContextResponse context(Long runId);
void appendEvent(Long runId, CreateAgentRunEventRequest request);
AgentToolCallResponse createToolCall(Long runId, CreateAgentToolCallRequest request);
void completeToolCall(Long toolCallId, CompleteAgentToolCallRequest request);
void failToolCall(Long toolCallId, FailAgentToolCallRequest request);
void completeRun(Long runId, CompleteAgentRunRequest request);
void failRun(Long runId, FailAgentRunRequest request);
```

### 13.3 AgentToolDescriptorService

Methods:

```java
List<AgentToolDescriptorResponse> listAvailableToolsForUser(Long userId);
AgentToolDescriptorResponse getToolForAgent(Long userId, String toolCode);
```

Phase 1.1 can ignore per-user tool permissions if current tool permissions are global, but the method signature must include `userId` for later extension.

---

## 14. Mapper Design

Use MyBatis-Plus `BaseMapper<T>` and annotation SQL, matching existing `TaskMapper`.

Each mapper should provide:

- insert methods through BaseMapper.
- select by id and user id.
- paginated list methods.
- count methods.
- status update methods.

Example `AgentRunMapper` methods:

```java
AgentRun selectByIdAndUserId(Long runId, Long userId);
AgentRun selectById(Long runId);
List<AgentRunEvent> findEvents(Long runId, Long userId, Long afterEventId, int limit);
void markRunning(Long runId);
void markSuccess(...);
void markFailed(...);
void markCancelled(...);
```

Use `LocalDateTime.now()` in service layer, not SQL `NOW()`, to keep tests deterministic where needed.

---

## 15. Error Codes

If existing `ErrorCode` enum does not contain these values, add:

```text
AGENT_SESSION_NOT_FOUND
AGENT_RUN_NOT_FOUND
AGENT_RUN_NOT_CANCELLABLE
AGENT_RATE_LIMITED
AGENT_ACTIVE_RUN_LIMIT
AGENT_TOOL_NOT_AVAILABLE
AGENT_CREDIT_NOT_ENOUGH
```

Messages:

```text
会话不存在
Agent 运行不存在
当前 Agent 运行不可取消
请求过于频繁，请稍后再试
已有 Agent 正在运行，请稍后再试
工具不可用
算力不足
```

---

## 16. Security Requirements

### 16.1 Public API

- Must require JWT.
- Must use `AuthContext`.
- Must enforce user ownership.
- Must never accept `userId` from request body.

### 16.2 Internal API

- Must live under `/api/internal/v1/agent/**`.
- Must rely on existing `AuthInterceptor` internal signature verification.
- Must never expose internal APIs through frontend.

### 16.3 Data Leakage Prevention

- `GET /sessions/{id}` cannot return another user's session.
- `GET /runs/{id}` cannot return another user's run.
- `GET /runs/{id}/events` cannot return another user's events.
- Internal context response must only contain the run owner's data.

---

## 17. Implementation Tasks

### Task 1: Add Agent SQL schema

**Files:**

- Create: `sql/005_agent_module.sql`
- Modify: `backend/src/test/resources/schema-test.sql`

- [ ] **Step 1: Add production SQL migration**

Add the five tables described in Section 4.

- [ ] **Step 2: Add test schema**

Mirror the same tables in `schema-test.sql`.

- [ ] **Step 3: Run backend tests**

Run:

```powershell
cd backend
mvn test
```

Expected:

```text
BUILD SUCCESS
```

### Task 2: Add entities and DTOs

**Files:**

- Create all files under `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/`
- Create all files under `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/`

- [ ] **Step 1: Add entity classes**

Create Java beans for `AgentSession`, `AgentMessage`, `AgentRun`, `AgentRunEvent`, and `AgentToolCall`.

- [ ] **Step 2: Add response DTO records**

Create DTO records described in Section 6.

- [ ] **Step 3: Compile**

Run:

```powershell
cd backend
mvn -q -DskipTests compile
```

Expected:

```text
Compilation succeeds
```

### Task 3: Add mappers

**Files:**

- Create: `AgentSessionMapper.java`
- Create: `AgentMessageMapper.java`
- Create: `AgentRunMapper.java`
- Create: `AgentRunEventMapper.java`
- Create: `AgentToolCallMapper.java`

- [ ] **Step 1: Implement BaseMapper interfaces**

Each mapper extends `BaseMapper<Entity>`.

- [ ] **Step 2: Add ownership queries**

Add select methods that always include `user_id` for public-facing reads.

- [ ] **Step 3: Add pagination queries**

Use `LIMIT #{limit} OFFSET #{offset}` and count queries.

- [ ] **Step 4: Add status update queries**

Add explicit update methods for run and tool call status transitions.

- [ ] **Step 5: Compile**

Run:

```powershell
cd backend
mvn -q -DskipTests compile
```

Expected:

```text
Compilation succeeds
```

### Task 4: Add service interfaces and implementations

**Files:**

- Create: service interfaces under `agent/service/`
- Create: implementations under `agent/service/impl/`

- [ ] **Step 1: Implement AgentSessionService**

Support create, list, detail, and message listing.

- [ ] **Step 2: Implement AgentRunService sendMessage**

Create user message, run, and initial events in one transaction.

- [ ] **Step 3: Implement run detail, cancel, and event listing**

Enforce ownership and status rules.

- [ ] **Step 4: Implement internal run context**

Return latest user message, history, available tools, and credit budget.

- [ ] **Step 5: Implement internal append/complete/fail methods**

Support Agent Service callbacks.

- [ ] **Step 6: Compile**

Run:

```powershell
cd backend
mvn -q -DskipTests compile
```

Expected:

```text
Compilation succeeds
```

### Task 5: Add public controllers

**Files:**

- Create: `AgentSessionController.java`
- Create: `AgentRunController.java`

- [ ] **Step 1: Add session endpoints**

Implement create/list/detail/messages/send message endpoints.

- [ ] **Step 2: Add run endpoints**

Implement run detail/cancel/events endpoints.

- [ ] **Step 3: Use ApiResponse**

Every endpoint returns `ApiResponse<T>`.

- [ ] **Step 4: Run API tests after Task 8**

Controller tests are added in Task 8.

### Task 6: Add internal controller

**Files:**

- Create: `InternalAgentController.java`

- [ ] **Step 1: Add context endpoint**

Implement `GET /api/internal/v1/agent/runs/{runId}/context`.

- [ ] **Step 2: Add event endpoint**

Implement `POST /api/internal/v1/agent/runs/{runId}/events`.

- [ ] **Step 3: Add tool call endpoints**

Implement create/complete/fail tool call endpoints.

- [ ] **Step 4: Add complete/fail run endpoints**

Implement complete and fail callbacks.

### Task 7: Add config and rate limit service

**Files:**

- Modify: `backend/src/main/resources/application.yml`
- Modify: `AppProperties.java`
- Modify: `StartupSecurityValidator.java` if production warning is added
- Create: `AgentRateLimitService.java`
- Create: `RedisAgentRateLimitService.java`

- [ ] **Step 1: Add config properties**

Add `app.agent.*`.

- [ ] **Step 2: Add Redis rate limit implementation**

Implement message rate and active run checks.

- [ ] **Step 3: Integrate into sendMessage**

Call rate limit service before creating run.

### Task 8: Add tests

**Files:**

- Create: `AgentSessionApiTest.java`
- Create: `AgentRunApiTest.java`
- Create: `InternalAgentApiTest.java`
- Create: `AgentSecurityApiTest.java`

- [ ] **Step 1: Test session create/list/detail**

Cases:

```text
logged-in user can create session
logged-in user can list own sessions
anonymous user cannot create session
```

- [ ] **Step 2: Test ownership**

Cases:

```text
user A cannot read user B session
user A cannot read user B run
user A cannot read user B events
```

- [ ] **Step 3: Test send message**

Cases:

```text
send message creates user message
send message creates run
send message creates run.started event
blank message returns validation error
```

- [ ] **Step 4: Test internal signature**

Cases:

```text
unsigned internal request is rejected
signed internal request can append event
signed internal request can complete run
```

- [ ] **Step 5: Test run completion**

Cases:

```text
complete run inserts assistant message
complete run marks status SUCCESS
complete run inserts run.completed event
fail run marks status FAILED
```

### Task 9: Update OpenAPI documentation

**Files:**

- Modify: `docs/api/openapi.yml`

- [ ] **Step 1: Add public Agent APIs**

Document session, message, run, cancel, and events endpoints.

- [ ] **Step 2: Add internal Agent APIs**

Document internal context, event, tool call, complete, and fail endpoints.

- [ ] **Step 3: Run OpenAPI contract test**

Run:

```powershell
cd backend
mvn test -Dtest=OpenApiContractTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 10: Final verification

- [ ] **Step 1: Run backend compile**

```powershell
cd backend
mvn -q -DskipTests compile
```

- [ ] **Step 2: Run targeted tests**

```powershell
cd backend
mvn test -Dtest=AgentSessionApiTest,AgentRunApiTest,InternalAgentApiTest,AgentSecurityApiTest
```

- [ ] **Step 3: Run full backend tests**

```powershell
cd backend
mvn test
```

- [ ] **Step 4: Manual smoke test**

Use curl or frontend client later to verify:

```text
login
create session
send message
get run
get events
signed internal complete run
list messages
```

---

## 18. Acceptance Criteria

Phase 1.1 is complete when:

- Five Agent tables exist in production SQL and test schema.
- Public Agent APIs compile and pass tests.
- Internal Agent APIs require HMAC signature.
- Users cannot access other users' Agent sessions, runs, messages, or events.
- Sending a message creates a user message, an Agent run, and an initial event.
- Internal complete callback creates an assistant message and marks run `SUCCESS`.
- Internal fail callback marks run `FAILED`.
- Tool call records can be created and completed by internal API.
- Agent tool descriptors can be generated from existing online tools.
- Rate limit hooks exist and are called by send message flow.
- OpenAPI documentation includes the new endpoints.
- Targeted Agent tests and existing backend tests pass.

---

## 19. Handoff To Next Phase

After this document is implemented, the next development document should be:

```text
Phase 1.2：FastAPI Agent Service 框架开发文档
```

That document will consume the internal APIs defined here:

- `GET /api/internal/v1/agent/runs/{runId}/context`
- `POST /api/internal/v1/agent/runs/{runId}/events`
- `POST /api/internal/v1/agent/runs/{runId}/tool-calls`
- `POST /api/internal/v1/agent/runs/{runId}/complete`
- `POST /api/internal/v1/agent/runs/{runId}/fail`

The FastAPI service should not change the Spring Boot business contract unless this Phase 1.1 document is revised first.
