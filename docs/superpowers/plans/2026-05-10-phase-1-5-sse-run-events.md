# Phase 1.5 SSE Streaming And Run Events Implementation Plan

> Superseded: Phase 1 implementation should now follow `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`. This file is retained only as historical detail.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace frontend polling with reliable Server-Sent Events for Agent run progress, while keeping persisted event replay, reconnect safety, ownership checks, and a path toward high-concurrency deployment.

**Architecture:** Spring Boot remains the only browser-facing streaming service in Phase 1. Agent Service writes run events to Spring Boot internal APIs; Spring Boot persists each event, publishes it to connected SSE clients, and supports replay via `afterEventId` / `Last-Event-ID`.

**Tech Stack:** Spring Boot `SseEmitter`, MyBatis-Plus, MySQL/H2, Redis Pub/Sub or Redis Stream for optional cross-instance fanout, Next.js `EventSource`, existing Agent run event table, existing JWT auth.

---

## 1. Scope

Phase 1.5 hardens the real-time event path.

It includes:

- Spring Boot SSE endpoint for Agent run events.
- Event persistence and replay rules.
- Last event id support.
- Frontend `EventSource` hook.
- Replacement of polling in `agent-web`.
- Heartbeat events.
- Disconnect and reconnect handling.
- User ownership checks.
- Emitter cleanup.
- Basic connection limits.
- Optional Redis fanout design for multi-instance backend.
- Tests for replay, ownership, terminal events, and emitter lifecycle.

It does not include:

- WebSocket.
- Direct browser connection to FastAPI Agent Service.
- Native model token streaming from model provider.
- RabbitMQ/Celery long task streaming.
- Full production streaming gateway.
- Kubernetes ingress tuning.

---

## 2. Existing Event Foundation

Previous phases define:

```text
agent_run_events
GET /api/v1/agent/runs/{runId}/events?afterEventId=0
POST /api/internal/v1/agent/runs/{runId}/events
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

Phase 1.5 keeps `agent_run_events` as the source of truth. SSE is a delivery optimization, not the only place where events live.

---

## 3. Target Flow

```text
agent-web
  -> send message
  -> receives runId
  -> opens EventSource /api/v1/agent/runs/{runId}/stream

agent-service
  -> appends events to Spring Boot internal API

Spring Boot
  -> inserts event into agent_run_events
  -> publishes event to active SseEmitters
  -> returns missed events on reconnect

agent-web
  -> updates assistant draft, run status, tool cards
  -> stops stream on terminal event
```

---

## 4. API Contract

### 4.1 Stream endpoint

```text
GET /api/v1/agent/runs/{runId}/stream
```

Query:

```text
afterEventId optional long
```

Headers:

```text
Authorization: Bearer <token>
Last-Event-ID: optional
```

Response:

```text
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

Ownership:

- current user must own the run.
- run must belong to an existing session owned by the user.

### 4.2 Replay endpoint remains

Keep:

```text
GET /api/v1/agent/runs/{runId}/events?afterEventId=0
```

This remains useful for:

- fallback if SSE fails.
- page refresh.
- debugging.
- mobile network recovery.

---

## 5. SSE Event Format

Use event id equal to `agent_run_events.id`.

Example:

```text
id: 10023
event: tool.selected
data: {"id":10023,"runId":90001,"eventType":"tool.selected","eventText":"已选择 AI 小红书文案生成器","eventJson":{"toolCode":"xiaohongshu_copywriting"},"createdAt":"2026-05-10T10:00:00"}

```

For heartbeat:

```text
event: heartbeat
data: {"ts":"2026-05-10T10:00:05"}

```

Do not persist heartbeat events.

---

## 6. Backend Design

### 6.1 New backend files

Create:

```text
backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/
  AgentRunSseController.java
  AgentRunEventStreamService.java
  AgentRunEventEmitterRegistry.java
  AgentRunEventPublisher.java
  AgentRunEventPayload.java
```

Tests:

```text
backend/src/test/java/com/aiminilab/aitoolmarket/agent/
  AgentRunSseApiTest.java
  AgentRunEventStreamServiceTest.java
```

### 6.2 Controller

```java
@GetMapping(
    value = "/api/v1/agent/runs/{runId}/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
)
public SseEmitter stream(@PathVariable Long runId,
                         @RequestParam(required = false) Long afterEventId,
                         HttpServletRequest request) {
    Long userId = AuthContext.get().userId();
    Long lastEventId = resolveLastEventId(afterEventId, request.getHeader("Last-Event-ID"));
    return streamService.open(userId, runId, lastEventId);
}
```

### 6.3 Stream service responsibilities

`AgentRunEventStreamService`:

- verify ownership.
- create `SseEmitter`.
- register emitter.
- replay missed events after `lastEventId`.
- send initial `connected` event.
- send terminal event if run already finished.
- clean up on completion, timeout, or error.

### 6.4 Emitter registry

`AgentRunEventEmitterRegistry` stores active emitters:

```text
Map<Long runId, Set<ClientEmitter>>
Map<Long userId, AtomicInteger activeConnectionCount>
```

`ClientEmitter`:

```text
emitterId
userId
runId
SseEmitter emitter
createdAt
lastSentEventId
```

Thread safety:

- Use `ConcurrentHashMap`.
- Use concurrent set per run.
- Remove emitter in all callbacks.

### 6.5 Timeout

Default:

```text
app.agent.sse.timeout-ms = 300000
```

5 minutes is enough for Phase 1 MVP.

Frontend reconnects if run is still active.

### 6.6 Heartbeat

Spring Boot sends heartbeat every:

```text
app.agent.sse.heartbeat-interval-seconds = 15
```

Reason:

- keeps proxies from closing idle connections.
- lets frontend know stream is alive.

Implementation:

- Scheduled task iterates active emitters.
- Sends `heartbeat`.
- Removes broken emitters.

---

## 7. Event Publish Path

When Agent Service calls:

```text
POST /api/internal/v1/agent/runs/{runId}/events
```

Spring Boot should:

```text
1. verify internal signature
2. validate run exists
3. insert agent_run_events row
4. return inserted event
5. publish inserted event to local SSE emitters
6. publish inserted event to Redis channel if enabled
```

Important change from Phase 1.1:

`appendEvent` should return `AgentRunEventResponse`, not void.

This gives the exact event id to both internal callers and stream publisher.

---

## 8. Replay Rules

### 8.1 On stream open

If `afterEventId` or `Last-Event-ID` is provided:

```text
replay events with id > lastEventId
```

If neither is provided:

```text
replay all run events up to configured limit
```

Default limit:

```text
app.agent.sse.initial-replay-limit = 200
```

### 8.2 If too many events

If missed event count exceeds limit:

- send `replay.truncated` event.
- frontend calls REST events endpoint to backfill.

### 8.3 Terminal runs

If run is already terminal:

- replay missed events.
- send terminal event if not already included.
- complete emitter shortly after.

Terminal statuses:

```text
SUCCESS
FAILED
TIMEOUT
CANCELLED
```

Terminal event types:

```text
run.completed
run.failed
run.cancelled
```

Add `run.cancelled` to event type list in Phase 1.5.

---

## 9. Redis Fanout Design

### 9.1 Why Redis fanout is needed

In multi-instance backend deployment:

```text
Agent event may be written by backend instance A
User SSE connection may be on backend instance B
```

Local in-memory emitters are not enough.

### 9.2 Phase 1.5 recommendation

Implement Redis Pub/Sub as optional:

```text
app.agent.sse.redis-fanout-enabled = false by default
```

Channel:

```text
agent:run-events
```

Payload:

```json
{
  "runId": 90001,
  "event": {}
}
```

When enabled:

- writer publishes event to Redis.
- every backend instance subscribes.
- each instance delivers event to its local emitters for that run.

### 9.3 Redis Stream later

Redis Stream can be used later for stronger delivery guarantees.

Phase 1.5 source of truth is still MySQL, so Pub/Sub is acceptable.

---

## 10. Connection Limits

Add config:

```yaml
app:
  agent:
    sse:
      max-connections-per-user: ${AGENT_SSE_MAX_CONNECTIONS_PER_USER:3}
      max-connections-total: ${AGENT_SSE_MAX_CONNECTIONS_TOTAL:1000}
```

Rules:

- If user exceeds max connections, reject with HTTP 429.
- If global limit is exceeded, reject with HTTP 503 or 429.
- Closing old duplicate run connections is optional; rejecting is simpler and safer.

---

## 11. Security

### 11.1 Auth

SSE endpoint requires the same JWT as other `/api/v1/**` endpoints.

Browser `EventSource` cannot set Authorization header in all native patterns. There are two options:

Option A:

```text
Use fetch-based SSE parser in frontend, so Authorization header can be set.
```

Option B:

```text
Issue short-lived stream token from backend, pass as query param.
```

Phase 1.5 recommendation:

```text
Use fetch-based SSE parser.
```

Reason:

- avoids token in URL.
- reuses existing Bearer token.
- works with current auth model.

Frontend can use:

- custom `fetch` + ReadableStream parser.
- or `@microsoft/fetch-event-source` if adding dependency is acceptable.

Recommended dependency:

```text
@microsoft/fetch-event-source
```

### 11.2 Ownership

Before opening stream:

- verify run belongs to current user.

Before replaying events:

- query by run id and user id.

Before publishing to emitter:

- emitter is registered under verified user/run pair.

### 11.3 Data leakage

Never broadcast events by user-independent global listener directly to browser.

Always route:

```text
runId -> verified local emitters for that run
```

---

## 12. Frontend Design

### 12.1 Replace polling hook

Modify:

```text
agent-web/lib/hooks/use-run-events.ts
```

Current Phase 1.3 behavior:

```text
poll GET /events every 1000ms
```

New behavior:

```text
open fetch-based SSE stream
apply events as they arrive
store lastEventId
reconnect with afterEventId
fallback to REST events endpoint if stream fails repeatedly
```

### 12.2 Add stream API helper

Create:

```text
agent-web/lib/api/agent-stream.ts
```

Function:

```ts
export function streamRunEvents(params: {
  runId: number
  afterEventId?: number
  signal: AbortSignal
  onEvent: (event: AgentRunEvent) => void
  onHeartbeat?: () => void
  onError?: (error: Error) => void
}): Promise<void>
```

### 12.3 Reconnect behavior

Policy:

```text
attempt 1: immediately
attempt 2: 1s
attempt 3: 2s
attempt 4+: 5s
max continuous retry window: 60s
```

If retry window exceeded:

- show reconnect failed message.
- fallback to polling every 3 seconds.

### 12.4 Last event id

Frontend tracks:

```text
lastEventIdRef
```

On reconnect:

```text
/stream?afterEventId={lastEventId}
```

### 12.5 Terminal handling

On terminal event:

- abort stream.
- refresh messages.
- refresh run.

Terminal events:

```text
run.completed
run.failed
run.cancelled
```

---

## 13. Agent Service Changes

Agent Service should not stream directly to frontend in Phase 1.5.

It only needs to:

- append smaller `message.delta` events for progressive UI.
- append `message.completed`.
- append terminal event.

If Phase 1.2 generated full text at once, Phase 1.5 can chunk it:

```text
split final answer into 20-80 character chunks
append each chunk as message.delta
sleep 10-30ms between chunks in local/mock mode if desired
```

Production model-native streaming is a later enhancement.

---

## 14. Backend File Changes

Create:

```text
backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunSseController.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunEventStreamService.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunEventEmitterRegistry.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunEventPublisher.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/stream/AgentRunEventPayload.java
```

Modify:

```text
backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentRunService.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java
backend/src/main/java/com/aiminilab/aitoolmarket/config/AppProperties.java
backend/src/main/resources/application.yml
docs/api/openapi.yml
```

Tests:

```text
backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentRunSseApiTest.java
backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentRunEventStreamServiceTest.java
```

---

## 15. Frontend File Changes

Create:

```text
agent-web/lib/api/agent-stream.ts
```

Modify:

```text
agent-web/lib/hooks/use-run-events.ts
agent-web/lib/api/agent.ts
agent-web/lib/types/agent.ts
agent-web/components/agent/run-status.tsx
agent-web/components/agent/run-event-list.tsx
agent-web/components/agent/message-list.tsx
```

Optional dependency:

```text
@microsoft/fetch-event-source
```

---

## 16. Testing Strategy

### 16.1 Backend tests

`AgentRunSseApiTest`:

```text
unauthenticated stream request rejected
user cannot stream another user's run
owner can open stream
stream replays existing events
stream respects afterEventId
terminal run closes after replay
connection limit rejects excess connections
```

`AgentRunEventStreamServiceTest`:

```text
register emitter
publish event to run emitter
does not publish to different run
removes emitter on completion
removes emitter on timeout
heartbeat removes broken emitter
```

### 16.2 Frontend tests/manual verification

Manual checks:

```text
send message opens stream
message.delta appears without polling delay
tool events update cards live
network disconnect reconnects with afterEventId
refresh page replays missed events
terminal event stops stream
cancel run stops stream
```

### 16.3 Integration smoke

```text
backend running
agent-service running
agent-web running
send agent message
observe live events
verify agent_run_events rows exist
refresh browser
verify state restores
```

---

## 17. Implementation Tasks

### Task 1: Add backend SSE configuration

**Files:**

- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/config/AppProperties.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Add config properties**

Add:

```yaml
app:
  agent:
    sse:
      timeout-ms: ${AGENT_SSE_TIMEOUT_MS:300000}
      heartbeat-interval-seconds: ${AGENT_SSE_HEARTBEAT_INTERVAL_SECONDS:15}
      initial-replay-limit: ${AGENT_SSE_INITIAL_REPLAY_LIMIT:200}
      max-connections-per-user: ${AGENT_SSE_MAX_CONNECTIONS_PER_USER:3}
      max-connections-total: ${AGENT_SSE_MAX_CONNECTIONS_TOTAL:1000}
      redis-fanout-enabled: ${AGENT_SSE_REDIS_FANOUT_ENABLED:false}
```

- [ ] **Step 2: Compile**

```powershell
cd backend
mvn -q -DskipTests compile
```

Expected:

```text
Compilation succeeds
```

### Task 2: Implement emitter registry

**Files:**

- Create: `AgentRunEventEmitterRegistry.java`
- Create: `AgentRunEventPayload.java`
- Create: `AgentRunEventStreamServiceTest.java`

- [ ] **Step 1: Write registry tests**

Cover register, publish, remove, per-run isolation.

- [ ] **Step 2: Implement registry**

Use thread-safe maps and cleanup callbacks.

- [ ] **Step 3: Add connection limit checks**

Per user and global.

- [ ] **Step 4: Run tests**

```powershell
cd backend
mvn test -Dtest=AgentRunEventStreamServiceTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 3: Implement stream service and controller

**Files:**

- Create: `AgentRunEventStreamService.java`
- Create: `AgentRunSseController.java`
- Create: `AgentRunSseApiTest.java`

- [ ] **Step 1: Write API tests**

Cover auth, ownership, replay, afterEventId.

- [ ] **Step 2: Implement stream open**

Verify run ownership, create SseEmitter, replay missed events.

- [ ] **Step 3: Implement heartbeat**

Scheduled heartbeat sends non-persisted heartbeat event.

- [ ] **Step 4: Run tests**

```powershell
cd backend
mvn test -Dtest=AgentRunSseApiTest,AgentRunEventStreamServiceTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 4: Publish events after persistence

**Files:**

- Create: `AgentRunEventPublisher.java`
- Modify: `AgentRunService.java`
- Modify: `AgentRunServiceImpl.java`
- Modify: `InternalAgentController.java`

- [ ] **Step 1: Change appendEvent return type**

Return `AgentRunEventResponse`.

- [ ] **Step 2: Publish inserted event**

After DB insert, call publisher.

- [ ] **Step 3: Ensure complete/fail emits terminal events**

Terminal event is persisted and published.

- [ ] **Step 4: Run Agent tests**

```powershell
cd backend
mvn test -Dtest=AgentRunApiTest,InternalAgentApiTest,AgentRunSseApiTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 5: Add optional Redis fanout

**Files:**

- Modify: `AgentRunEventPublisher.java`
- Create: `AgentRunEventRedisSubscriber.java`

- [ ] **Step 1: Add config guard**

Only enable when `redis-fanout-enabled=true`.

- [ ] **Step 2: Publish event payload to Redis**

Use channel `agent:run-events`.

- [ ] **Step 3: Subscribe and deliver local**

Subscriber receives payload and publishes to local registry.

- [ ] **Step 4: Add focused tests if Redis test support exists**

If no Redis test support exists, document manual verification and keep unit coverage on serialization.

### Task 6: Update OpenAPI docs

**Files:**

- Modify: `docs/api/openapi.yml`

- [ ] **Step 1: Add stream endpoint**

Document `GET /api/v1/agent/runs/{runId}/stream`.

- [ ] **Step 2: Add event types**

Include `run.cancelled` and `heartbeat`.

- [ ] **Step 3: Run contract test**

```powershell
cd backend
mvn test -Dtest=OpenApiContractTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 7: Add frontend stream client

**Files:**

- Create: `agent-web/lib/api/agent-stream.ts`
- Modify: `agent-web/package.json`

- [ ] **Step 1: Add fetch-event-source dependency**

Install:

```powershell
cd agent-web
npm install @microsoft/fetch-event-source
```

- [ ] **Step 2: Implement streamRunEvents**

Attach Authorization header and parse events.

- [ ] **Step 3: Support abort signal**

Caller can stop stream on terminal event or route change.

- [ ] **Step 4: Typecheck**

```powershell
cd agent-web
npm run typecheck
```

Expected:

```text
No TypeScript errors
```

### Task 8: Replace polling hook with SSE

**Files:**

- Modify: `agent-web/lib/hooks/use-run-events.ts`
- Modify: `agent-web/lib/api/agent.ts`

- [ ] **Step 1: Keep hook API stable**

Return the same shape as Phase 1.3.

- [ ] **Step 2: Open stream when run starts**

Use `streamRunEvents`.

- [ ] **Step 3: Track lastEventId**

Reconnect with `afterEventId`.

- [ ] **Step 4: Add fallback polling**

After repeated stream failure, poll REST endpoint every 3 seconds.

- [ ] **Step 5: Stop on terminal events**

Abort stream and refresh messages.

### Task 9: Update frontend event UI

**Files:**

- Modify: `agent-web/lib/types/agent.ts`
- Modify: `agent-web/components/agent/run-status.tsx`
- Modify: `agent-web/components/agent/run-event-list.tsx`
- Modify: `agent-web/components/agent/message-list.tsx`

- [ ] **Step 1: Add event types**

Add `heartbeat`, `run.cancelled`, `replay.truncated`.

- [ ] **Step 2: Hide heartbeat from timeline**

Heartbeat should not clutter UI.

- [ ] **Step 3: Show reconnecting state**

Show compact status when stream reconnects.

- [ ] **Step 4: Validate mobile layout**

No status text overlaps composer.

### Task 10: Agent Service chunked deltas

**Files:**

- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Modify: `agent-service/app/core/runtime.py`
- Modify: `agent-service/tests/test_universal_graph.py`

- [ ] **Step 1: Split final answer into chunks**

Emit multiple `message.delta` events.

- [ ] **Step 2: Emit message.completed**

After all deltas.

- [ ] **Step 3: Keep final run completion**

Still call complete run with full final answer.

- [ ] **Step 4: Run tests**

```powershell
cd agent-service
pytest tests/test_universal_graph.py -q
```

Expected:

```text
All tests pass
```

### Task 11: End-to-end verification

- [ ] **Step 1: Backend targeted tests**

```powershell
cd backend
mvn test -Dtest=AgentRunSseApiTest,AgentRunEventStreamServiceTest,AgentRunApiTest,InternalAgentApiTest
```

- [ ] **Step 2: Agent Service tests**

```powershell
cd agent-service
pytest tests/test_universal_graph.py -q
```

- [ ] **Step 3: Frontend typecheck and build**

```powershell
cd agent-web
npm run typecheck
npm run build
```

- [ ] **Step 4: Manual browser check**

```text
send message
see deltas appear live
see tool events appear live
disconnect/reconnect network
refresh and replay missed events
terminal event stops stream
```

---

## 18. Acceptance Criteria

Phase 1.5 is complete when:

- Spring Boot exposes `GET /api/v1/agent/runs/{runId}/stream`.
- SSE endpoint requires auth and enforces run ownership.
- Existing persisted events replay on stream open.
- `afterEventId` and `Last-Event-ID` are supported.
- Internal event append persists then publishes to active SSE clients.
- Frontend receives events through fetch-based SSE with Authorization header.
- Frontend no longer depends on 1-second polling during normal operation.
- Frontend reconnects with last event id.
- Frontend falls back to REST polling if SSE repeatedly fails.
- Heartbeats keep idle streams alive and are hidden from UI.
- Terminal events stop streaming and refresh messages.
- Connection limits prevent unbounded SSE growth.
- Backend tests, Agent Service tests, and frontend typecheck/build pass.

---

## 19. Handoff To Next Phase

After this document is implemented, the next development document should be:

```text
Phase 1.6：Agent 算力计费、限流与安全开发文档
```

That document should harden:

- exact credit reservation and settlement.
- per-user and per-run cost budgets.
- model/tool usage accounting.
- active run concurrency limits.
- abuse prevention.
- production security settings.
