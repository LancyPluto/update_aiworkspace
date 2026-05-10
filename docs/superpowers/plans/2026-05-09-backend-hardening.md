# Backend Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strengthen the backend security, reliability, and performance baseline so the project can move from V1 demo quality toward a small production-ready service.

**Architecture:** Keep the current Spring Boot layered structure, but tighten cross-cutting infrastructure first: configuration validation, authentication boundaries, reliable task dispatch, explicit task state transitions, and observability. Each task is independently testable and should be committed separately.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis-Plus 3.5.9, MySQL, Redis, JUnit 5, MockMvc, HikariCP.

---

## Current Risk Summary

The backend already has a clear V1 structure, but enterprise projects usually require stronger guardrails around secrets, authentication, state transitions, async dispatch, logging, and database access patterns.

Highest-risk files found during review:

- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/aiminilab/aitoolmarket/config/CorsConfig.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/config/AuthInterceptor.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/auth/security/JwtTokenProvider.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/TaskQueuePublisher.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/mapper/TaskMapper.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/common/exception/GlobalExceptionHandler.java`

---

## Implementation Order

1. Security baseline: production config validation, CORS whitelist, JWT hardening, internal API signature.
2. Reliability baseline: task state machine, reliable queue publishing, idempotent worker callbacks.
3. Observability baseline: request trace id, structured error logging, actuator and metrics.
4. Performance baseline: pagination strategy, batch result loading, indexes, connection pool tuning.

---

### Task 1: Production Configuration Guardrails

**Files:**

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/config/AppProperties.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/config/StartupSecurityValidator.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/config/StartupSecurityValidatorTest.java`

- [x] **Step 1: Add typed application properties**

Create `AppProperties` with fields for `jwtSecret`, `internalApiToken`, `aiTaskQueue`, `corsAllowedOrigins`, and `productionMode`.

Expected behavior:

- Local development may use default secrets.
- Production mode must reject weak default secrets.
- CORS origins must be configurable without code changes.

- [x] **Step 2: Add startup validation**

`StartupSecurityValidator` should fail application startup when any of these are true:

- `app.production-mode=true` and `app.jwt-secret=local-dev-secret`
- `app.production-mode=true` and `app.internal-api-token=local-internal-token`
- `app.production-mode=true` and JWT secret length is less than 32 characters
- `app.production-mode=true` and CORS origins contain `*`

- [x] **Step 3: Update configuration**

Add these keys to `application.yml`:

```yaml
app:
  production-mode: ${APP_PRODUCTION_MODE:false}
  jwt-secret: ${JWT_SECRET:local-dev-secret}
  internal-api-token: ${INTERNAL_API_TOKEN:local-internal-token}
  ai-task-queue: ${AI_TASK_QUEUE:ai:task:queue}
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174}
```

- [x] **Step 4: Add tests**

Test cases:

- Production mode with default JWT secret fails.
- Production mode with default internal token fails.
- Production mode with wildcard CORS fails.
- Development mode with defaults starts.

- [x] **Step 5: Verify**

Run:

```bash
mvn test
```

Expected: all tests pass.

---

### Task 2: CORS Whitelist Hardening

**Files:**

- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/config/CorsConfig.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/config/CorsConfigTest.java`

- [x] **Step 1: Replace wildcard origin**

Change `allowedOriginPatterns("*")` to values from `app.cors.allowed-origins`.

Implementation rule:

- Keep `allowCredentials(true)` only when using explicit origins.
- Do not allow wildcard origin in production mode.

- [x] **Step 2: Add tests**

Test cases:

- Configured local frontend origin is allowed.
- Unknown origin is not allowed.
- Credentialed requests are only allowed for configured origins.

- [x] **Step 3: Verify**

Run:

```bash
mvn test -Dtest=CorsConfigTest
```

Expected: CORS tests pass.

---

### Task 3: JWT Authentication Hardening

**Files:**

- Modify: `backend/pom.xml`
- Replace: `backend/src/main/java/com/aiminilab/aitoolmarket/auth/security/JwtTokenProvider.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/auth/controller/AdminAuthController.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/auth/security/TokenDenylistService.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/auth/JwtTokenProviderTest.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/auth/AuthLogoutApiTest.java`

- [x] **Step 1: Use a mature JWT library**

Add a maintained JWT library such as `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, and `jjwt-jackson`.

JWT payload should include:

- `sub`: user id
- `username`
- `userType`
- `iss`: `ai-tool-market-backend`
- `aud`: `ai-tool-market-client`
- `jti`: random token id
- `exp`: expiration time

- [x] **Step 2: Add token denylist**

Use Redis to store revoked token ids until their original expiration.

Interface:

```java
public interface TokenDenylistService {
    void deny(String tokenId, Duration ttl);
    boolean isDenied(String tokenId);
}
```

- [x] **Step 3: Make logout real**

`/logout` should:

- Parse the current bearer token.
- Read the `jti`.
- Add it to denylist until expiration.
- Return success even if token is already denied.

- [x] **Step 4: Add tests**

Test cases:

- Valid token parses successfully.
- Token with wrong issuer is rejected.
- Token with wrong audience is rejected.
- Expired token is rejected.
- Logout invalidates token.

- [x] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=JwtTokenProviderTest,AuthLogoutApiTest
```

Expected: authentication hardening tests pass.

---

### Task 4: Internal API Request Signature

**Files:**

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/auth/security/InternalRequestSignatureVerifier.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/config/AuthInterceptor.java`
- Modify: `worker/client/backend_client.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/auth/InternalRequestSignatureVerifierTest.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/task/WorkerInternalApiSecurityTest.java`

- [x] **Step 1: Define signature headers**

Required headers:

- `X-Internal-Timestamp`: epoch milliseconds
- `X-Internal-Nonce`: random uuid
- `X-Internal-Signature`: HMAC-SHA256 signature

Signature content:

```text
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256_HEX(BODY)
```

- [x] **Step 2: Verify timestamp and nonce**

Rules:

- Reject requests older than 5 minutes.
- Store nonce in Redis for 5 minutes.
- Reject reused nonce.
- Compare signatures in constant time.

- [x] **Step 3: Update Worker client**

`worker/client/backend_client.py` should generate the timestamp, nonce, body hash, and HMAC signature for every `/api/internal/v1/**` request.

- [x] **Step 4: Add tests**

Test cases:

- Valid signature passes.
- Wrong body fails.
- Old timestamp fails.
- Reused nonce fails.
- Missing signature fails.

- [x] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=InternalRequestSignatureVerifierTest,WorkerInternalApiSecurityTest
```

Expected: internal API security tests pass.

---

### Task 5: Task State Machine and Idempotent Worker Callbacks

**Files:**

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/TaskStateMachine.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/mapper/TaskMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/task/TaskStateMachineTest.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/task/WorkerCallbackIdempotencyTest.java`

- [x] **Step 1: Centralize allowed transitions**

Allowed transitions:

- `QUEUED -> PROCESSING`
- `QUEUED -> CANCELLED`
- `PROCESSING -> SUCCESS`
- `PROCESSING -> FAILED`
- `PROCESSING -> CANCELLED`
- `FAILED -> QUEUED`

Terminal states:

- `SUCCESS`
- `CANCELLED`

- [x] **Step 2: Add conditional SQL updates**

Every status update in `TaskMapper` should include expected current states:

```sql
WHERE id = #{taskId}
  AND status IN (...)
```

Mapper methods should return affected row count.

- [x] **Step 3: Make worker success idempotent**

Rules:

- If task is already `SUCCESS`, return current status without inserting another result or deducting credits again.
- If task is `CANCELLED`, ignore worker success and return current status.
- If task is `FAILED`, do not allow success unless explicit retry moved it back to `QUEUED`.

- [x] **Step 4: Make failure/release idempotent**

Rules:

- If task is already `FAILED`, return current status without releasing credits again.
- If task is `SUCCESS`, do not release credits.
- If task is `CANCELLED`, do not release credits again.

- [x] **Step 5: Add tests**

Test cases:

- Repeated success callback deducts credits once.
- Repeated failed callback releases credits once.
- Cancel then success callback does not deduct credits.
- Admin retry from `FAILED` returns task to `QUEUED`.
- Invalid transition returns business error.

- [x] **Step 6: Verify**

Run:

```bash
mvn test -Dtest=TaskStateMachineTest,WorkerCallbackIdempotencyTest,TaskCreditApiTest,WorkerInternalApiTest,AdminTaskApiTest
```

Expected: task state and credit tests pass.

---

### Task 6: Reliable Task Dispatch with Outbox

**Files:**

- Modify: `sql/001_init_v1.sql`
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/task/entity/TaskOutboxEvent.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/task/mapper/TaskOutboxMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/TaskOutboxService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/TaskQueuePublisher.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/task/TaskOutboxServiceTest.java`

- [ ] **Step 1: Add outbox table**

Create table:

```sql
CREATE TABLE task_outbox_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_outbox_status_retry (status, next_retry_at, id),
    UNIQUE KEY uk_task_outbox_task_event (task_id, event_type)
);
```

- [ ] **Step 2: Write outbox event in the same transaction as task creation**

`TaskServiceImpl.createNewTask` should:

- Insert task.
- Freeze credits.
- Insert outbox event.
- Return response.

It should not directly publish to Redis inside the request transaction.

- [ ] **Step 3: Add dispatcher**

`TaskOutboxService` should:

- Poll pending events.
- Publish to Redis.
- Mark event as `SENT` after successful publish.
- Increase `retry_count` and set `next_retry_at` after failure.

- [ ] **Step 4: Add tests**

Test cases:

- Task creation writes one outbox event.
- Redis publish failure keeps event pending.
- Retry later sends pending event.
- Duplicate create with same idempotency key does not create duplicate outbox event.

- [ ] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=TaskOutboxServiceTest,TaskCreditApiTest
```

Expected: outbox tests and task credit tests pass.

---

### Task 7: Request Trace ID and Error Logging

**Files:**

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/config/TraceIdFilter.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/common/dto/ApiResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/common/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/common/TraceIdApiTest.java`

- [x] **Step 1: Add trace id filter**

Rules:

- Read `X-Request-Id` if present and safe.
- Generate UUID if missing.
- Put trace id into MDC.
- Add `X-Request-Id` to response headers.
- Clear MDC after request.

- [x] **Step 2: Include trace id in API error responses**

`ApiResponse` should include a nullable `traceId` field.

Success responses may omit it; error responses should include it.

- [x] **Step 3: Log exceptions**

`GlobalExceptionHandler` should:

- Log `BusinessException` at warn level with error code and trace id.
- Log unknown exceptions at error level with stack trace and trace id.
- Keep client response generic for unknown exceptions.

- [x] **Step 4: Add tests**

Test cases:

- Response contains `X-Request-Id`.
- Error response contains same trace id.
- Provided valid `X-Request-Id` is preserved.

- [x] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=TraceIdApiTest
```

Expected: trace id tests pass.

---

### Task 8: Actuator and Basic Metrics

**Files:**

- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/task/metrics/TaskMetrics.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/common/ActuatorEndpointTest.java`

- [ ] **Step 1: Add actuator**

Add dependency:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 2: Expose safe endpoints**

Configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 3: Add task metrics**

Metrics:

- `ai_task_success_total`
- `ai_task_failed_total`
- `ai_task_cancelled_total`
- `ai_task_queue_publish_failed_total`

- [ ] **Step 4: Add tests**

Test cases:

- `/actuator/health` returns 200.
- Task success increments success counter.
- Task failure increments failure counter.

- [ ] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=ActuatorEndpointTest,WorkerInternalApiTest
```

Expected: actuator and task tests pass.

---

### Task 9: List Query Performance

**Files:**

- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/mapper/TaskMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/common/dto/PageResponse.java`
- Modify: `sql/001_init_v1.sql`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/task/TaskListPerformanceShapeTest.java`

- [ ] **Step 1: Remove N+1 result loading from task list**

`TaskServiceImpl.list` and `adminList` should not call `findFirstResult` for every task.

Rules:

- List endpoint returns task summary fields.
- Detail endpoint returns task result.
- If list must include result preview, add a batch method: `findFirstResultsByTaskIds(List<Long> taskIds)`.

- [ ] **Step 2: Add cursor pagination for high-volume task lists**

Add optional `lastId` support:

- First page: no `lastId`.
- Next page: `WHERE t.id < #{lastId}`.
- Keep `ORDER BY t.id DESC LIMIT #{limit}`.

- [ ] **Step 3: Add indexes**

Add indexes:

```sql
CREATE INDEX idx_ai_tasks_user_status_id ON ai_tasks (user_id, status, id);
CREATE INDEX idx_ai_tasks_user_id ON ai_tasks (user_id, id);
CREATE INDEX idx_ai_tasks_status_id ON ai_tasks (status, id);
CREATE INDEX idx_ai_tasks_tool_id ON ai_tasks (tool_id, id);
CREATE INDEX idx_ai_result_resources_task_order ON ai_result_resources (task_id, sort_order, id);
CREATE INDEX idx_ai_tools_status_category_id ON ai_tools (status, category_id, id);
```

- [ ] **Step 4: Add tests**

Test cases:

- Task list endpoint does not require result rows.
- Cursor pagination returns stable descending order.
- `hasNext` works when one extra row is fetched.

- [ ] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=TaskListPerformanceShapeTest,AdminTaskApiTest
```

Expected: list performance tests pass.

---

### Task 10: Database Migration Discipline

**Files:**

- Modify: `backend/pom.xml`
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `backend/src/main/resources/db/migration/V2__task_outbox_events.sql`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/common/DatabaseMigrationTest.java`

- [ ] **Step 1: Add Flyway**

Add dependency:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

- [ ] **Step 2: Move schema into versioned migrations**

Use `sql/001_init_v1.sql` as the source for `V1__init_schema.sql`.

Add task outbox changes in `V2__task_outbox_events.sql`.

- [ ] **Step 3: Configure Flyway**

Configuration:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

- [ ] **Step 4: Add migration test**

Test should boot an H2 or MySQL-compatible test database, run migrations, and verify these tables exist:

- `users`
- `ai_tools`
- `ai_tasks`
- `credit_accounts`
- `task_outbox_events`

- [ ] **Step 5: Verify**

Run:

```bash
mvn test -Dtest=DatabaseMigrationTest
```

Expected: migration test passes.

---

## Acceptance Checklist

- [ ] Production mode refuses default JWT secret and internal API token.
- [ ] CORS no longer uses wildcard origins when credentials are enabled.
- [ ] JWT validation uses issuer, audience, expiration, and token id.
- [ ] Logout revokes the current token until expiration.
- [ ] Internal Worker API rejects unsigned, stale, or replayed requests.
- [ ] Task status updates use explicit state transitions and affected-row checks.
- [ ] Repeated Worker callbacks do not double deduct or double release credits.
- [ ] Task dispatch survives Redis temporary failure through outbox retry.
- [ ] Every error response can be correlated with `X-Request-Id`.
- [ ] Unknown exceptions are logged with stack trace and trace id.
- [ ] Actuator health and metrics endpoints are available with safe exposure.
- [ ] Task list endpoints avoid N+1 result queries.
- [ ] High-volume task lists support cursor pagination.
- [ ] Core task/tool/credit tables have indexes matching common query filters.
- [ ] Database schema changes are versioned through Flyway.
- [ ] Full backend test suite passes with `mvn test`.

---

## Suggested Commit Sequence

1. `chore: add production configuration guardrails`
2. `fix: restrict cors origins by configuration`
3. `feat: harden jwt auth and logout`
4. `feat: sign internal worker requests`
5. `fix: enforce task state transitions`
6. `feat: add reliable task outbox dispatch`
7. `chore: add request trace logging`
8. `chore: expose actuator health and metrics`
9. `perf: optimize task list queries`
10. `chore: version database schema with flyway`

---

## Notes for Future Implementation

- Implement tasks in order. Later tasks assume earlier security and state-machine guardrails exist.
- Keep each task small and separately tested.
- Prefer adding tests before changing production code.
- Preserve current public API response shapes unless a task explicitly changes them.
- Do not remove existing V1 tests; they are the regression safety net.
