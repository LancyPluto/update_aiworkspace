# Workflow Tools P0 V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 AI 漫剧工作流做成 `/agents` 与 `/agent` 共用的可发布、可恢复、分步计费且异步运行的正式工具能力。

**Architecture:** 继续复用 Spring Boot、MySQL、RabbitMQ、现有 Python Worker 与 root task，不增加微服务。Spring Boot 内部以 `WorkflowRunApplicationService` 作为唯一创建入口，运行时固定不可变发布版本，并将逻辑 step、执行 attempt、费用预留和用户确认分开持久化；`/agents` 和 Agent 内部上下文只调用应用服务，不通过公开 Controller 互调。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus、MySQL 8、JUnit 5、MockMvc、Python 3/Pytest、Vue 3、TypeScript、Vite、Node test runner。

---

## 文件职责图

- `sql/088_workflow_tools_p0.sql`：生产数据库增量迁移，建立不可变版本引用、CAS revision、attempt、charge、confirmation 与幂等键。
- `backend/src/test/resources/schema-test.sql`：与生产迁移等价的 H2 测试结构。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/model/*`：工作流运行、步骤、attempt、费用和确认状态枚举。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/entity/*`、`workflow/mapper/*`：持久化模型及条件更新。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowRunApplicationService.java`：`/agents` 与 `/agent` 共用的幂等创建、查询、取消和恢复入口。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowStepScheduler.java`：线性 DAG 推进和 attempt 调度。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowBillingService.java`：调度前预留、成功捕获、失败释放及对账。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowInteractionService.java`：一次性确认、补充输入、余额恢复和取消。
- `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/controller/WorkflowRunController.java`、`workflow/dto/*`：用户工作流工具及运行页 API。
- `backend/src/main/java/com/aiminilab/aitoolmarket/agent/*`：内部 descriptor 增强与 `DELEGATED` tool call 生命周期。
- `agent-service/app/tools/backend_tool.py`：工作流工具创建成功后立即返回运行卡片，不等待最终完成。
- `user-web/src/pages/AgentPlaceholder/*`、`user-web/src/pages/WorkflowRun/*`：工作流工具中心、详情及通用运行页。

### Task 1: 数据库契约与状态枚举

**Files:**
- Create: `sql/088_workflow_tools_p0.sql`
- Modify: `backend/src/test/resources/schema-test.sql`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/common/enums/TaskStatus.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/TaskStateMachine.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/model/WorkflowRunStatus.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/model/WorkflowStepStatus.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/model/WorkflowAttemptStatus.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/model/WorkflowChargeStatus.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowSchemaContractTest.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/task/TaskStateMachineTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void workflowTablesExposeVersionRevisionAttemptAndChargeColumns() throws Exception {
    assertColumn("WORKFLOW_RUNS", "WORKFLOW_VERSION_ID");
    assertColumn("WORKFLOW_RUNS", "REVISION");
    assertColumn("WORKFLOW_RUN_STEPS", "REVISION");
    assertColumn("WORKFLOW_STEP_ATTEMPTS", "IDEMPOTENCY_KEY");
    assertColumn("WORKFLOW_STEP_CHARGES", "STATUS");
    assertColumn("WORKFLOW_CONFIRMATIONS", "TOKEN_HASH");
}

@Test
void awaitingFundsCanResumeOrCancel() {
    assertDoesNotThrow(() -> TaskStateMachine.ensureTransition("AWAITING_FUNDS", "PROCESSING"));
    assertDoesNotThrow(() -> TaskStateMachine.ensureTransition("AWAITING_FUNDS", "CANCELLED"));
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowSchemaContractTest,TaskStateMachineTest test`

Expected: FAIL，提示缺少 `WORKFLOW_STEP_ATTEMPTS` 表或 `AWAITING_FUNDS` 枚举值。

- [ ] **Step 3: 写最小迁移和枚举实现**

```sql
ALTER TABLE tool_workflows ADD COLUMN draft_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE tool_workflows ADD COLUMN published_version_id BIGINT NULL;
ALTER TABLE tool_workflows ADD COLUMN execution_enabled TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE ai_tools ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE ai_tools ADD COLUMN billing_mode VARCHAR(32) NOT NULL DEFAULT 'FIXED';
ALTER TABLE ai_tools ADD COLUMN agent_surface_enabled TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE ai_tools ADD COLUMN minimum_required_credits INT NOT NULL DEFAULT 0;
ALTER TABLE tool_workflow_versions ADD COLUMN canonical_dsl_json JSON NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN dsl_version VARCHAR(32) NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN node_registry_version VARCHAR(32) NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN dsl_hash CHAR(64) NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN input_schema_snapshot_json JSON NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN dependency_manifest_json JSON NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN billing_policy_json JSON NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN risk_policy_json JSON NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN source_draft_revision BIGINT NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN published_at DATETIME NULL;
ALTER TABLE tool_workflow_versions ADD COLUMN published_by BIGINT NULL;
ALTER TABLE workflow_runs ADD COLUMN workflow_version_id BIGINT NULL;
ALTER TABLE workflow_runs ADD COLUMN launch_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_TASK';
ALTER TABLE workflow_runs ADD COLUMN client_request_id VARCHAR(128) NULL;
ALTER TABLE workflow_runs ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workflow_runs ADD COLUMN cancellation_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workflow_runs ADD COLUMN current_step_id BIGINT NULL;
ALTER TABLE workflow_runs ADD COLUMN billing_status VARCHAR(32) NOT NULL DEFAULT 'CLEAR';
ALTER TABLE workflow_runs ADD COLUMN started_at DATETIME NULL;
ALTER TABLE workflow_runs ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE workflow_runs ADD UNIQUE KEY uk_workflow_run_root_task(root_task_id);
ALTER TABLE workflow_runs ADD UNIQUE KEY uk_workflow_run_user_request(user_id, client_request_id);
ALTER TABLE workflow_run_steps ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workflow_run_steps ADD COLUMN sequence_no INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_run_steps ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_run_steps ADD COLUMN current_attempt_id BIGINT NULL;

CREATE TABLE workflow_step_attempts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  step_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  child_task_id BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  claim_token VARCHAR(128) NOT NULL,
  provider_code VARCHAR(64) NULL,
  provider_request_id VARCHAR(128) NULL,
  input_json JSON NULL,
  output_json JSON NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(2000) NULL,
  lease_expires_at DATETIME NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_attempt_step_no(step_id, attempt_no),
  UNIQUE KEY uk_workflow_attempt_claim_token(claim_token),
  UNIQUE KEY uk_workflow_attempt_task(child_task_id),
  UNIQUE KEY uk_workflow_attempt_provider(provider_code, provider_request_id)
);

CREATE TABLE workflow_step_charges (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  step_id BIGINT NOT NULL,
  attempt_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reserved_credits INT NOT NULL,
  charged_credits INT NOT NULL DEFAULT 0,
  provider_cost DECIMAL(18,6) NULL,
  provider_cost_currency VARCHAR(8) NULL,
  status VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  credit_log_id BIGINT NULL,
  billing_usage_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_charge_attempt(attempt_id),
  UNIQUE KEY uk_workflow_charge_idempotency(idempotency_key)
);

CREATE TABLE workflow_confirmations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  step_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  parameter_hash CHAR(64) NOT NULL,
  allowed_actions_json JSON NOT NULL,
  decision VARCHAR(32) NULL,
  feedback_json JSON NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_confirmation_token(token_hash)
);
```

```java
public enum WorkflowRunStatus {
    RUNNING, AWAITING_USER, AWAITING_FUNDS, CANCELLING, SUCCESS, FAILED, TIMEOUT, CANCELLED
}

public enum WorkflowStepStatus {
    PENDING, READY, QUEUED, RUNNING, AWAITING_USER, SUCCESS, FAILED, CANCELLED
}

public enum WorkflowAttemptStatus {
    CREATED, QUEUED, RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED, SUPERSEDED
}

public enum WorkflowChargeStatus {
    RESERVED, CAPTURED, RELEASED
}
```

- [ ] **Step 4: 运行聚焦测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowSchemaContractTest,TaskStateMachineTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add sql/088_workflow_tools_p0.sql backend/src/test/resources/schema-test.sql backend/src/main/java/com/aiminilab/aitoolmarket/common/enums/TaskStatus.java backend/src/main/java/com/aiminilab/aitoolmarket/task/service/TaskStateMachine.java backend/src/main/java/com/aiminilab/aitoolmarket/workflow/model backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowSchemaContractTest.java backend/src/test/java/com/aiminilab/aitoolmarket/task/TaskStateMachineTest.java
git commit -m "feat: add workflow run persistence contract"
```

### Task 2: 不可变发布版本与草稿并发控制

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/entity/ToolWorkflow.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/entity/ToolWorkflowVersion.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/mapper/ToolWorkflowMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/mapper/ToolWorkflowVersionMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/service/impl/WorkflowServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/dto/UpsertWorkflowRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/dto/WorkflowResponse.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/tool/AdminWorkflowApiTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void publishCreatesImmutableVersionAndStaleDraftSaveReturnsConflict() throws Exception {
    long workflowId = createDraft();
    long revision = getRevision(workflowId);
    saveDraft(workflowId, revision, "first edit").andExpect(status().isOk());
    saveDraft(workflowId, revision, "stale edit").andExpect(status().isConflict());
    publish(workflowId).andExpect(jsonPath("$.data.publishedVersionId").isNumber());
    assertThat(versionMapper.selectPublishedByWorkflowId(workflowId)).hasSize(1);
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=AdminWorkflowApiTest#publishCreatesImmutableVersionAndStaleDraftSaveReturnsConflict test`

Expected: FAIL，旧接口没有 `draftRevision` 与 `publishedVersionId`。

- [ ] **Step 3: 实现 CAS 保存和正式发布**

```java
@Transactional
public WorkflowResponse saveWorkflow(Long toolId, UpsertWorkflowRequest request, Long operatorId) {
    ToolWorkflow current = workflowMapper.selectByToolId(toolId);
    if (current == null) {
        return createDraft(toolId, request, operatorId);
    }
    int changed = workflowMapper.updateDraftIfRevision(
            current.getId(), request.nodesJson(), request.edgesJson(), request.groupsJson(),
            request.configJson(), request.expectedDraftRevision(), operatorId);
    if (changed != 1) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "草稿已被其他人更新，请刷新后重试");
    }
    return toResponse(workflowMapper.selectById(current.getId()));
}

@Transactional
public WorkflowResponse publish(Long workflowId, Long operatorId) {
    ToolWorkflow draft = requireWorkflow(workflowId);
    workflowDslService.validateDraft(draft);
    ToolWorkflowVersion version = ToolWorkflowVersion.publishedSnapshot(draft, nextVersion(draft), operatorId);
    versionMapper.insert(version);
    if (workflowMapper.bindPublishedVersion(workflowId, version.getId(), draft.getDraftRevision(), operatorId) != 1) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "发布前草稿已变化，请重新检查");
    }
    return toResponse(workflowMapper.selectById(workflowId));
}
```

- [ ] **Step 4: 运行管理 API 回归**

Run: `mvn -f backend/pom.xml -Dtest=AdminWorkflowApiTest test`

Expected: PASS，旧的读取、版本列表和恢复用例仍通过。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/tool backend/src/test/java/com/aiminilab/aitoolmarket/tool/AdminWorkflowApiTest.java
git commit -m "feat: publish immutable workflow versions"
```

### Task 3: Run、Step、Attempt 的实体和 CAS Mapper

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/entity/WorkflowRun.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/entity/WorkflowRunStep.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/entity/WorkflowStepAttempt.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/entity/WorkflowStepCharge.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/entity/WorkflowConfirmation.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/mapper/WorkflowRunMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/mapper/WorkflowRunStepMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/mapper/WorkflowStepAttemptMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/mapper/WorkflowStepChargeMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/mapper/WorkflowConfirmationMapper.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowCasMapperTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void runAndStepOnlyAdvanceFromExpectedRevisionAndAttemptKeyIsUnique() {
    WorkflowRun run = fixtures.insertRun(0L);
    assertThat(runMapper.casStatus(run.getId(), 0L, "RUNNING", "AWAITING_USER", null)).isEqualTo(1);
    assertThat(runMapper.casStatus(run.getId(), 0L, "RUNNING", "FAILED", "late callback")).isZero();

    WorkflowRunStep step = fixtures.insertStep(run.getId(), 0L);
    attemptMapper.insert(fixtures.attempt(step.getId(), 1, "step:%d:1".formatted(step.getId())));
    assertThatThrownBy(() -> attemptMapper.insert(fixtures.attempt(step.getId(), 1, "duplicate")))
            .isInstanceOf(DuplicateKeyException.class);
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowCasMapperTest test`

Expected: FAIL，缺少 `casStatus` 与 attempt mapper。

- [ ] **Step 3: 实现条件更新**

```java
@Update("""
    UPDATE workflow_runs
       SET status=#{nextStatus}, error_message=#{errorMessage}, revision=revision+1, updated_at=NOW()
     WHERE id=#{runId} AND revision=#{revision} AND status=#{expectedStatus}
    """)
int casStatus(Long runId, long revision, String expectedStatus, String nextStatus, String errorMessage);

@Update("""
    UPDATE workflow_run_steps
       SET status=#{nextStatus}, revision=revision+1, updated_at=NOW()
     WHERE id=#{stepId} AND revision=#{revision} AND status=#{expectedStatus}
    """)
int casStatus(Long stepId, long revision, String expectedStatus, String nextStatus);
```

- [ ] **Step 4: 运行测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowCasMapperTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowCasMapperTest.java
git commit -m "feat: add workflow CAS persistence"
```

### Task 4: 共用的幂等 Workflow Run Application Service

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/CreateWorkflowRunCommand.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/WorkflowRunCreated.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowRunApplicationService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowExecutionService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowRunApplicationServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void sameUserAndIdempotencyKeyReturnsSameRootTaskAndPinnedVersion() {
    CreateWorkflowRunCommand command = new CreateWorkflowRunCommand(
            7L, "ai_comic_drama_agent", Map.of("prompt", "城市夜景"), "request-123", "AGENTS_PAGE", null);
    WorkflowRunCreated first = service.create(command);
    publishNewWorkflowVersion();
    WorkflowRunCreated repeated = service.create(command);
    assertThat(repeated.rootTaskId()).isEqualTo(first.rootTaskId());
    assertThat(repeated.workflowVersionId()).isEqualTo(first.workflowVersionId());
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowRunApplicationServiceTest test`

Expected: FAIL，`WorkflowRunApplicationService` 尚不存在。

- [ ] **Step 3: 实现唯一创建入口**

```java
@Transactional
public WorkflowRunCreated create(CreateWorkflowRunCommand command) {
    WorkflowRun existing = runMapper.selectByUserAndIdempotencyKey(command.userId(), command.idempotencyKey());
    if (existing != null) return toCreated(existing);
    AiTool tool = toolMapper.findEnabledByCode(command.toolCode()).orElseThrow(this::toolNotFound);
    ToolWorkflow workflow = workflowMapper.selectPublishedByToolId(tool.getId());
    ToolWorkflowVersion version = versionMapper.selectById(workflow.getPublishedVersionId());
    AiTask root = taskService.createWorkflowRoot(command.userId(), tool.getId(), command.input());
    WorkflowRun run = WorkflowRun.create(root.getId(), command, workflow.getId(), version.getId(), version.getVersion());
    try {
        runMapper.insert(run);
    } catch (DuplicateKeyException duplicate) {
        return toCreated(runMapper.selectByUserAndIdempotencyKey(command.userId(), command.idempotencyKey()));
    }
    executionService.initializeAndAdvance(run.getId(), version);
    return toCreated(run);
}
```

- [ ] **Step 4: 运行应用服务测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowRunApplicationServiceTest test`

Expected: PASS，重复请求不产生第二个 root task。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/TaskServiceImpl.java backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowRunApplicationServiceTest.java
git commit -m "feat: add idempotent workflow run service"
```

### Task 5: Step Attempt 调度、回调隔离和重启恢复

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowStepScheduler.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowStepCallbackService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowRecoveryScheduler.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowExecutionService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowStepSchedulerTest.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowLateCallbackTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void retryCreatesNewAttemptAndLateFirstCallbackCannotAdvanceStep() {
    WorkflowStepAttempt first = scheduler.dispatch(stepId);
    callbacks.failed(first.getTaskId(), "timeout");
    WorkflowStepAttempt second = scheduler.retry(stepId);
    callbacks.succeeded(first.getTaskId(), output("late"));
    assertThat(stepMapper.selectById(stepId).getCurrentAttemptId()).isEqualTo(second.getId());
    assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("RUNNING");
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowStepSchedulerTest,WorkflowLateCallbackTest test`

Expected: FAIL，旧实现直接用 step 的 `task_id` 和 `attempt`，无法隔离晚到回调。

- [ ] **Step 3: 以 active attempt 推进**

```java
@Transactional
public void succeeded(long taskId, WorkerSuccessRequest request) {
    WorkflowStepAttempt attempt = attemptMapper.selectByTaskId(taskId);
    if (attempt == null || !attempt.isActive()) return;
    WorkflowRunStep step = stepMapper.selectById(attempt.getStepId());
    if (!attempt.getId().equals(step.getCurrentAttemptId())) {
        attemptMapper.markSuperseded(attempt.getId());
        return;
    }
    if (attemptMapper.markSuccessIfRunning(attempt.getId(), serialize(request)) != 1) return;
    if (stepMapper.markSuccessForAttempt(step.getId(), step.getRevision(), attempt.getId()) != 1) return;
    billingService.capture(attempt.getId());
    executionService.advanceRun(step.getRunId());
}
```

- [ ] **Step 4: 运行调度和回调测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowStepSchedulerTest,WorkflowLateCallbackTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/main/java/com/aiminilab/aitoolmarket/task/service/impl/InternalTaskServiceImpl.java backend/src/test/java/com/aiminilab/aitoolmarket/workflow
git commit -m "feat: isolate workflow step attempts"
```

### Task 6: 分步费用预留、捕获、释放和对账

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowBillingService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowChargeReconciler.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/CreditService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/impl/CreditServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/common/enums/CreditSourceType.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowBillingServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void dispatchRequiresReservationAndCallbacksSettleExactlyOnce() {
    long attemptId = fixtures.createdAttempt(20);
    assertThat(billing.reserve(attemptId)).isEqualTo(ReservationResult.RESERVED);
    billing.capture(attemptId, 18);
    billing.capture(attemptId, 18);
    assertThat(creditBalance()).isEqualTo(initialBalance - 18);
    assertThat(charge(attemptId).getStatus()).isEqualTo("CAPTURED");
}

@Test
void insufficientFundsPausesRunBeforeProviderDispatch() {
    assertThat(billing.reserve(fixtures.createdAttempt(2000))).isEqualTo(ReservationResult.INSUFFICIENT);
    assertThat(runStatus()).isEqualTo("AWAITING_FUNDS");
    verifyNoOutboxMessage();
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowBillingServiceTest test`

Expected: FAIL，现有逻辑在成功回调后才扣费。

- [ ] **Step 3: 实现账本状态机**

```java
@Transactional
public ReservationResult reserve(long attemptId) {
    WorkflowStepAttempt attempt = requireAttempt(attemptId);
    WorkflowStepCharge existing = chargeMapper.selectByAttemptId(attemptId);
    if (existing != null) return ReservationResult.valueOf(existing.getStatus());
    int estimated = quote(attempt);
    if (creditService.freeze(attempt.getUserId(), CreditSourceType.WORKFLOW_STEP, attemptId, estimated) != 1) {
        pauseForFunds(attempt);
        return ReservationResult.INSUFFICIENT;
    }
    chargeMapper.insert(WorkflowStepCharge.reserved(attempt, estimated));
    return ReservationResult.RESERVED;
}

@Transactional
public void release(long attemptId) {
    WorkflowStepCharge charge = chargeMapper.selectByAttemptId(attemptId);
    if (charge == null || chargeMapper.markReleasedIfReserved(charge.getId()) != 1) return;
    creditService.refundFrozen(charge.getUserId(), CreditSourceType.WORKFLOW_STEP, attemptId, charge.getReservedCredits());
}
```

- [ ] **Step 4: 运行计费测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowBillingServiceTest test`

Expected: PASS，重复捕获/释放不会重复改变余额。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/main/java/com/aiminilab/aitoolmarket/credit backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowBillingServiceTest.java
git commit -m "feat: settle workflow step credits safely"
```

### Task 7: 用户确认、余额恢复、取消与晚到事件

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowInteractionService.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/controller/WorkflowFeedbackController.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/WorkflowConfirmationRequest.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/WorkflowResumeRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/task/controller/TaskController.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowInteractionApiTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void confirmationTokenIsSingleUseAndCancelStopsAllFutureAdvancement() throws Exception {
    String token = fixtures.awaitingConfirmationRun();
    confirm(token).andExpect(status().isOk());
    confirm(token).andExpect(status().isConflict());
    cancelRun().andExpect(status().isOk());
    callbacks.succeeded(fixtures.activeTaskId(), output("late"));
    assertThat(fixtures.runStatus()).isEqualTo("CANCELLED");
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowInteractionApiTest test`

Expected: FAIL，当前 `USER_CONFIRM` 不会真正暂停，取消只处理 root task。

- [ ] **Step 3: 实现一次性消费和级联取消**

```java
@Transactional
public WorkflowRunDetail confirm(long rootTaskId, long userId, String rawToken, boolean accepted, Map<String, Object> input) {
    WorkflowConfirmation confirmation = confirmationMapper.selectForUpdate(hash(rawToken));
    validateOwnerAndExpiry(confirmation, rootTaskId, userId);
    if (confirmationMapper.consumeIfPending(confirmation.getId(), serialize(input)) != 1) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "确认已处理");
    }
    if (!accepted) return cancel(rootTaskId, userId, "USER_REJECTED");
    resumeAwaitingUser(confirmation, input);
    return query(rootTaskId, userId);
}

@Transactional
public WorkflowRunDetail cancel(long rootTaskId, long userId, String reason) {
    WorkflowRun run = requireOwnedRun(rootTaskId, userId);
    if (run.isTerminal()) return query(rootTaskId, userId);
    runMapper.cancelIfActive(run.getId(), run.getRevision(), reason);
    stepMapper.cancelActiveByRunId(run.getId());
    attemptMapper.cancelActiveByRunId(run.getId());
    chargeMapper.selectReservedByRunId(run.getId()).forEach(c -> billingService.release(c.getAttemptId()));
    taskService.cancelWorkflowTree(rootTaskId, reason);
    return query(rootTaskId, userId);
}
```

- [ ] **Step 4: 运行交互测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowInteractionApiTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/main/java/com/aiminilab/aitoolmarket/task backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowInteractionApiTest.java
git commit -m "feat: support workflow confirmation and cancellation"
```

### Task 8: `/agents` 用户 API

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/controller/WorkflowRunController.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/WorkflowToolSummaryResponse.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/WorkflowToolDetailResponse.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/CreateWorkflowRunRequest.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/dto/WorkflowRunDetailResponse.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowToolQueryService.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowRunApiTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void userCanListCreateAndReadOnlyOwnWorkflowRuns() throws Exception {
    mvc.perform(get("/api/v1/agents/tools").with(user(7L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].toolCode").value("ai_comic_drama_agent"));
    String taskId = createRunAs(7L);
    mvc.perform(get("/api/v1/agents/runs/{taskId}", taskId).with(user(8L)))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowRunApiTest test`

Expected: FAIL，路由尚不存在。

- [ ] **Step 3: 实现薄 Controller**

```java
@RestController
@RequestMapping("/api/v1/agents")
public class WorkflowRunController {
    @GetMapping("/tools")
    public ApiResponse<List<WorkflowToolSummaryResponse>> list() {
        return ApiResponse.success(queryService.listForUser(UserContext.requireUserId()));
    }

    @PostMapping("/tools/{toolCode}/runs")
    public ApiResponse<WorkflowRunCreated> create(@PathVariable String toolCode,
                                                   @Valid @RequestBody CreateWorkflowRunRequest request) {
        return ApiResponse.success(runService.create(request.toCommand(UserContext.requireUserId(), toolCode, "AGENTS_PAGE")));
    }

    @GetMapping("/runs/{rootTaskId}")
    public ApiResponse<WorkflowRunDetailResponse> detail(@PathVariable long rootTaskId) {
        return ApiResponse.success(runService.query(rootTaskId, UserContext.requireUserId()));
    }
}
```

- [ ] **Step 4: 运行 API 测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowRunApiTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowRunApiTest.java
git commit -m "feat: expose workflow tools user api"
```

### Task 9: `/agents` 工具中心和通用运行页

**Files:**
- Create: `user-web/src/api/workflowApi.ts`
- Create: `user-web/src/pages/AgentPlaceholder/WorkflowToolDetail.vue`
- Create: `user-web/src/pages/WorkflowRun/index.vue`
- Create: `user-web/src/components/workflow/WorkflowStepList.vue`
- Create: `user-web/src/components/workflow/WorkflowCostSummary.vue`
- Create: `user-web/src/components/workflow/WorkflowArtifactViewer.vue`
- Modify: `user-web/src/pages/AgentPlaceholder/index.vue`
- Modify: `user-web/src/router/index.ts`
- Modify: `user-web/src/router/userRoutes.ts`
- Test: `user-web/src/workflowRoutes.test.mjs`
- Test: `user-web/src/workflowRunView.test.mjs`

- [ ] **Step 1: 写失败测试**

```javascript
test('workflow routes keep old studio links compatible', () => {
  assert.equal(source.includes('path: "/agents/tools/:toolCode"'), true)
  assert.equal(source.includes('path: "/agents/runs/:taskId"'), true)
  assert.equal(source.includes('redirect: to => ({ name: "WorkflowRun", params: { taskId: to.params.taskId } })'), true)
})

test('run view renders status, steps, costs, artifacts and actions', () => {
  for (const marker of ['WorkflowStepList', 'WorkflowCostSummary', 'WorkflowArtifactViewer', 'cancelRun', 'confirmRun']) {
    assert.equal(runView.includes(marker), true)
  }
})
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `node --test user-web/src/workflowRoutes.test.mjs user-web/src/workflowRunView.test.mjs`

Expected: FAIL，缺少新页面和路由。

- [ ] **Step 3: 实现路由、API 和页面主流程**

```ts
export const workflowApi = {
  listTools: () => api.get<WorkflowToolSummary[]>("/api/v1/agents/tools"),
  getTool: (toolCode: string) => api.get<WorkflowToolDetail>(`/api/v1/agents/tools/${toolCode}`),
  createRun: (toolCode: string, request: CreateWorkflowRunRequest) =>
    api.post<WorkflowRunCreated>(`/api/v1/agents/tools/${toolCode}/runs`, request),
  getRun: (taskId: string) => api.get<WorkflowRunDetail>(`/api/v1/agents/runs/${taskId}`),
  cancelRun: (taskId: string) => api.post(`/api/v1/agents/runs/${taskId}/cancel`),
  resumeRun: (taskId: string) => api.post(`/api/v1/agents/runs/${taskId}/resume`),
  submitFeedback: (taskId: string, body: WorkflowConfirmationRequest) =>
    api.post(`/api/v1/agents/runs/${taskId}/feedback`, body),
}
```

```ts
{ path: "/agents/tools/:toolCode", name: "WorkflowToolDetail", component: () => import("../pages/AgentPlaceholder/WorkflowToolDetail.vue") },
{ path: "/agents/runs/:taskId", name: "WorkflowRun", component: () => import("../pages/WorkflowRun/index.vue") },
{ path: "/workflow/studio/:taskId", redirect: to => ({ name: "WorkflowRun", params: { taskId: to.params.taskId } }) },
```

- [ ] **Step 4: 运行前端测试和构建**

Run: `node --test user-web/src/workflowRoutes.test.mjs user-web/src/workflowRunView.test.mjs`

Expected: PASS。

Run: `npm --prefix user-web run build`

Expected: PASS，无 TypeScript 或 Vite 构建错误。

- [ ] **Step 5: 提交**

```bash
git add user-web/src/api/workflowApi.ts user-web/src/pages/AgentPlaceholder user-web/src/pages/WorkflowRun user-web/src/components/workflow user-web/src/router user-web/src/workflowRoutes.test.mjs user-web/src/workflowRunView.test.mjs
git commit -m "feat: add workflow tool center and run view"
```

### Task 10: `/agent` 共用 Descriptor 和 `DELEGATED` 异步调用

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolDescriptorResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolDescriptorServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentToolCall.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentToolCallMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `agent-service/app/tools/schemas.py`
- Modify: `agent-service/app/tools/backend_tool.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolDescriptorServiceImplTest.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentDelegatedToolCallTest.java`
- Test: `agent-service/tests/test_backend_tool_bridge.py`

- [ ] **Step 1: 写失败测试**

```java
@Test
void workflowDescriptorIsInternalOnlyAndPickerContractStaysUnchanged() {
    AgentToolDescriptorResponse descriptor = service.getToolForAgent(7L, "ai_comic_drama_agent");
    assertThat(descriptor.executionMode()).isEqualTo("WORKFLOW");
    assertThat(descriptor.billingMode()).isEqualTo("PER_STEP");
    assertThat(pickerJson()).doesNotContain("executionMode", "billingMode");
}

@Test
void delegatedToolCallCanCompleteAfterAgentRunTerminates() {
    AgentToolCall call = fixtures.delegatedCallForCompletedRun();
    assertThat(service.completeToolCall(call.getId(), success())).extracting(AgentToolCallResponse::status)
            .isEqualTo("SUCCESS");
}
```

```python
async def test_workflow_tool_returns_delegated_card_without_polling(monkeypatch):
    tool = backend_tool(execution_mode="WORKFLOW")
    result = await tool.execute_with_args({"prompt": "城市夜景"})
    assert result["status"] == "DELEGATED"
    assert result["runUrl"].endswith(f"/agents/runs/{result['taskId']}")
    assert polling_call_count() == 0
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=AgentToolDescriptorServiceImplTest,AgentDelegatedToolCallTest test`

Expected: FAIL，descriptor 缺少执行模式，终态 Agent run 会把 tool call 标成 `RUN_ALREADY_TERMINATED`。

Run: `pytest -q agent-service/tests/test_backend_tool_bridge.py`

Expected: FAIL，工作流仍进入 `_wait_for_task`。

- [ ] **Step 3: 实现共用能力和异步返回**

```java
public record AgentToolDescriptorResponse(
        String toolCode, String name, String description, Integer creditCost, JsonNode inputSchema,
        boolean autoCallable, List<AgentToolFieldResponse> fields, AgentToolHintsResponse agentHints,
        String executionMode, String billingMode, Integer minimumRequiredCredits, String runRouteTemplate,
        String riskLevel, String confirmationPolicy) {}
```

```python
created = await self._create_backend_task(arguments)
if self.descriptor.execution_mode == "WORKFLOW":
    await self._backend.complete_tool_call(
        self.tool_call_id,
        {"status": "DELEGATED", "taskId": created.task_id, "runUrl": f"/agents/runs/{created.task_id}"},
    )
    return {
        "status": "DELEGATED",
        "taskId": created.task_id,
        "runUrl": f"/agents/runs/{created.task_id}",
        "message": "工作流已开始，可在运行页查看进度",
    }
return await self._wait_for_task(created.task_id)
```

- [ ] **Step 4: 运行后端和 Agent 服务测试**

Run: `mvn -f backend/pom.xml -Dtest=AgentToolDescriptorServiceImplTest,AgentDelegatedToolCallTest test`

Expected: PASS，`GET /api/v1/agent/tools` 现有 JSON 契约测试不变。

Run: `pytest -q agent-service/tests/test_backend_tool_bridge.py agent-service/tests/test_backend_tool_visible_content.py`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent backend/src/test/java/com/aiminilab/aitoolmarket/agent agent-service/app/tools agent-service/tests/test_backend_tool_bridge.py agent-service/tests/test_backend_tool_visible_content.py
git commit -m "feat: delegate workflow tool calls from agent"
```

### Task 11: 开关、指标、恢复扫描和对账

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/config/WorkflowRuntimeProperties.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/metrics/WorkflowMetrics.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowRecoveryScheduler.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/workflow/service/WorkflowChargeReconciler.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-prod.yml`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowRecoverySchedulerTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void recoveryRequeuesOnlyExpiredActiveAttemptAndReconcilesReservedCharge() {
    fixtures.runningAttemptOlderThanMinutes(20);
    fixtures.runningAttemptOlderThanMinutes(2);
    scheduler.recover();
    assertThat(fixtures.requeuedAttempts()).hasSize(1);
    assertThat(fixtures.releasedOrCapturedCharges()).hasSize(1);
    assertThat(meterRegistry.counter("workflow_recovery_total", "result", "requeued").count()).isEqualTo(1);
}
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowRecoverySchedulerTest test`

Expected: FAIL，缺少恢复扫描和指标。

- [ ] **Step 3: 实现受开关控制的定时任务**

```java
@Scheduled(fixedDelayString = "${workflow.runtime.recovery-interval:PT1M}")
public void recover() {
    if (!properties.isEnabled()) return;
    for (WorkflowStepAttempt attempt : attemptMapper.selectExpiredActive(properties.getAttemptTimeout())) {
        recoveryService.recoverAttempt(attempt.getId());
    }
    chargeReconciler.reconcile(properties.getReconciliationBatchSize());
}
```

```yaml
workflow:
  runtime:
    enabled: false
    allowed-user-ids: []
    attempt-timeout: PT15M
    recovery-interval: PT1M
    reconciliation-batch-size: 100
```

- [ ] **Step 4: 运行恢复测试**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowRecoverySchedulerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/workflow backend/src/main/resources/application.yml backend/src/main/resources/application-prod.yml backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowRecoverySchedulerTest.java
git commit -m "feat: add workflow recovery controls"
```

### Task 12: 端到端、故障注入与发布门禁复核

**Files:**
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowP0EndToEndTest.java`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/workflow/WorkflowFailureInjectionTest.java`
- Create: `agent-service/tests/test_workflow_tool_delegation.py`
- Create: `user-web/src/workflowRunPolling.test.mjs`
- Modify: `docs/superpowers/specs/2026-07-13-workflow-tools-p0-v2-design.md`

- [ ] **Step 1: 写端到端验收测试**

```java
@Test
void comicWorkflowRunsFromAgentThroughConfirmationAndChargesEachStepOnce() {
    AgentToolCallResponse delegated = fixtures.invokeFromAgent("ai_comic_drama_agent", validComicInput());
    assertThat(delegated.status()).isEqualTo("DELEGATED");
    fixtures.completeFirstStep(delegated.taskId());
    fixtures.confirmCurrentStep(delegated.taskId());
    fixtures.completeRemainingSteps(delegated.taskId());
    assertThat(fixtures.run(delegated.taskId()).getStatus()).isEqualTo("SUCCESS");
    assertThat(fixtures.capturedCharges(delegated.taskId())).allMatch(WorkflowStepCharge::isCaptured);
    assertThat(fixtures.agentToolCall(delegated.id()).getTaskId()).isEqualTo(delegated.taskId());
}

@ParameterizedTest
@ValueSource(strings = {"DUPLICATE_CREATE", "DUPLICATE_CALLBACK", "CANCEL_DURING_RUN", "RESTART_DURING_RUN", "INSUFFICIENT_FUNDS"})
void failureInjectionPreservesSingleRunAndLedger(String scenario) {
    FailureResult result = fixtures.runFailureScenario(scenario);
    assertThat(result.duplicateRootTasks()).isZero();
    assertThat(result.duplicateCharges()).isZero();
    assertThat(result.illegalTransitions()).isZero();
}
```

- [ ] **Step 2: 运行测试并确认它能发现尚未补齐的集成问题**

Run: `mvn -f backend/pom.xml -Dtest=WorkflowP0EndToEndTest,WorkflowFailureInjectionTest test`

Expected: 首次运行若失败，只允许是明确的跨模块契约缺口；逐项修正生产代码，不放宽断言。

- [ ] **Step 3: 完成最小集成修正并记录发布门禁**

```markdown
## 12. P0 发布门禁执行结果

- 数据迁移在空库和现有结构上均成功执行。
- 生产迁移前必须查询历史 `(user_id, idempotency_key)` 重复；完成重复数据治理并保留零重复查询结果作为证据后，才允许创建或启用唯一约束。
- 发布前至少执行一次真实 MySQL/InnoDB 并发门禁，覆盖外层 `REPEATABLE READ` 与恢复事务 `READ COMMITTED`；可使用测试环境或 Testcontainers，本次 H2 修复测试不能替代该门禁。
- 同一幂等键只创建一个 root task 和一个 workflow run。
- run 固定 `workflow_version_id`，发布新版本不影响运行中任务。
- 每个付费 attempt 在出队前存在 `RESERVED` 费用记录。
- 重复回调、取消后晚到回调不会重复推进或重复扣费。
- `/agent` 返回 `DELEGATED` 运行卡片，`agent_tool_call.task_id` 可追溯。
- `/agents/runs/:taskId` 可完成确认、取消、充值后恢复和产物查看。
```

- [ ] **Step 4: 运行完整复核**

Run: `mvn -f backend/pom.xml test`

Expected: PASS。

Run: `pytest -q agent-service/tests`

Expected: PASS。

Run: `node --test user-web/src/*.test.mjs user-web/src/utils/*.test.mjs`

Expected: PASS。

Run: `npm --prefix user-web run build`

Expected: PASS。

Run: `git diff --check`

Expected: 无输出，退出码 0。

- [ ] **Step 5: 提交**

```bash
git add backend/src/test/java/com/aiminilab/aitoolmarket/workflow agent-service/tests/test_workflow_tool_delegation.py user-web/src/workflowRunPolling.test.mjs docs/superpowers/specs/2026-07-13-workflow-tools-p0-v2-design.md
git commit -m "test: verify workflow tools p0 end to end"
```

## 实施顺序与检查点

1. Task 1-4 完成后复核“不可变发布版本 + 幂等创建”，不接真实供应商。
2. Task 5-7 完成后复核“attempt 隔离 + 资金正确性 + 取消/确认”。
3. Task 8-10 完成后复核 `/agents` 与 `/agent` 是否确实共享应用服务。
4. Task 11-12 完成后进行全量测试、故障注入和代码审查。
5. 默认保持 `workflow.runtime.enabled=false`；只有测试环境验证通过后才由运维按灰度账号开启，生产环境不在本计划执行会话中手工修改。
