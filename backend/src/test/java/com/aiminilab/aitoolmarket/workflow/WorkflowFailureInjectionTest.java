package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.agent.dto.DelegatedWorkflowToolCallResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkflowDelegationService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowChargeReconciler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowInteractionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmission;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmissionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_p0_failure_injection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "workflow.runtime.enabled=true",
        "workflow.runtime.execution-enabled=true",
        "workflow.runtime.real-billing-enabled=true",
        "workflow.runtime.confirmation-enabled=false",
        "workflow.runtime.canary-percentage=100",
        "workflow.runtime.max-run-cost-credits=100",
        "workflow.runtime.max-user-daily-cost-credits=100",
        "spring.task.scheduling.enabled=false"
})
class WorkflowFailureInjectionTest {

    private static final long USER_ID = 7201L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    private WorkflowRunApplicationService runApplicationService;

    @Autowired
    private WorkflowRuntimeAdmissionService admissionService;

    @Autowired
    private WorkflowInteractionService interactionService;

    @Autowired
    private WorkflowStepCallbackService callbackService;

    @Autowired
    private AgentWorkflowDelegationService delegationService;

    @Autowired
    private WorkflowChargeReconciler chargeReconciler;

    @Autowired
    private WorkflowRuntimeGate runtimeGate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowRecoveryScheduler workflowRecoveryScheduler;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_tool_calls WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_sessions WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_confirmations WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_step_charges WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_step_attempts WHERE step_id IN (SELECT id FROM workflow_run_steps WHERE run_id IN (SELECT id FROM workflow_runs WHERE user_id = ?))", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_run_steps WHERE run_id IN (SELECT id FROM workflow_runs WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_runs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM task_outbox_events WHERE task_id IN (SELECT id FROM ai_tasks WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM billing_usage_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM ai_tasks WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tool_workflow_versions WHERE workflow_id IN (SELECT id FROM tool_workflows WHERE tool_id IN (SELECT id FROM ai_tools WHERE tool_code LIKE 'p0_failure_%'))");
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE tool_id IN (SELECT id FROM ai_tools WHERE tool_code LIKE 'p0_failure_%')");
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code LIKE 'p0_failure_%'");
        runtimeGate.markReconciliationHealthyAfterFullScan(
                runtimeGate.reconciliationFailureGeneration()
        );
    }

    @Test
    void fixedSnapshotBudgetUsesShanghaiDayBoundariesAndRejectsDailyOverflow() {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_daily", 40, 5);
        LocalDate today = LocalDate.now(SHANGHAI);
        insertCapturedUsage("daily-yesterday", 90, today.minusDays(1).atTime(23, 59, 59));
        insertCapturedUsage("daily-today", 50, today.atStartOfDay());
        insertCapturedUsage("daily-next-day", 90, today.plusDays(1).atStartOfDay());

        WorkflowRuntimeAdmission admitted = admissionService.admitNewRun(USER_ID, published.toolId());

        assertThat(admitted.paidRun()).isTrue();
        assertThat(admitted.estimatedRunCredits()).isEqualTo(40L);

        insertCapturedUsage("daily-overflow", 20, today.atTime(12, 0));
        assertBlocked(
                () -> admissionService.admitNewRun(USER_ID, published.toolId()),
                "user_daily_cost_limit_exceeded"
        );
    }

    @Test
    void providerActualCostDoesNotLimitUnifiedCreditBilling() {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_provider_daily", 10, 5);
        LocalDate today = LocalDate.now(SHANGHAI);
        insertProviderUsage("provider-yesterday", "40.00", today.minusDays(1).atTime(23, 59, 59));
        insertProviderUsage("provider-today", "99.90", today.atStartOfDay());
        insertProviderUsage("provider-next-day", "40.00", today.plusDays(1).atStartOfDay());

        assertThat(admissionService.admitNewRun(USER_ID, published.toolId()).paidRun()).isTrue();

        insertProviderUsage("provider-limit", "0.01", today.atTime(12, 0));
        assertThat(admissionService.admitNewRun(USER_ID, published.toolId()).paidRun()).isTrue();
    }

    @Test
    void nonCnyProviderCostDoesNotBlockUnifiedCreditBilling() {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_provider_currency", 10, 5);
        insertProviderUsage("provider-usd", "1.00", "USD", LocalDate.now(SHANGHAI).atStartOfDay());

        assertThat(admissionService.admitNewRun(USER_ID, published.toolId()).paidRun()).isTrue();
    }

    @Test
    void confirmationWorkflowCannotStartWhenConfirmationFeatureIsOff() {
        PublishedWorkflow published = insertConfirmationWorkflow("p0_failure_confirmation");

        assertBlocked(
                () -> admissionService.admitNewRun(USER_ID, published.toolId()),
                "confirmation_disabled"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isZero();
    }

    @Test
    void insufficientFundsCreatesOnlyRootRunAndReadyStepThenCancelIsIdempotent() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_funds", 10, 5);
        insertCreditAccount(0);

        WorkflowRunCreated created = runApplicationService.create(command(
                published.toolCode(),
                "p0-failure-funds"
        ));

        assertThat(runStatus(created.runId())).isEqualTo("AWAITING_FUNDS");
        assertThat(rootStatus(created.rootTaskId())).isEqualTo("AWAITING_FUNDS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id IN (SELECT id FROM workflow_run_steps WHERE run_id = ?)",
                Integer.class,
                created.runId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ?",
                Integer.class,
                created.runId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id <> ? AND task_id IN (SELECT id FROM ai_tasks WHERE user_id = ?)",
                Integer.class,
                created.rootTaskId(),
                USER_ID
        )).isZero();

        interactionService.cancel(created.rootTaskId(), USER_ID, "USER_CANCELLED");
        interactionService.cancel(created.rootTaskId(), USER_ID, "USER_CANCELLED");

        assertThat(runStatus(created.runId())).isEqualTo("CANCELLED");
        assertThat(rootStatus(created.rootTaskId())).isEqualTo("CANCELLED");
    }

    @Test
    void cancelAndDuplicateLateCallbackReleaseExactlyOnceWithoutChargingUser() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_cancel", 10, 5);
        insertCreditAccount(100);
        WorkflowRunCreated created = runApplicationService.create(command(
                published.toolCode(),
                "p0-failure-cancel"
        ));
        Long childTaskId = jdbcTemplate.queryForObject(
                "SELECT child_task_id FROM workflow_step_attempts WHERE step_id IN (SELECT id FROM workflow_run_steps WHERE run_id = ?)",
                Long.class,
                created.runId()
        );

        interactionService.cancel(created.rootTaskId(), USER_ID, "USER_CANCELLED");
        interactionService.cancel(created.rootTaskId(), USER_ID, "USER_CANCELLED");
        WorkerSuccessRequest lateSuccess = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", 10, 5, 1, "late-claim"
        );

        for (int i = 0; i < 100; i++) {
            assertThat(callbackService.succeeded(childTaskId, lateSuccess)).isFalse();
        }

        assertThat(runStatus(created.runId())).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE run_id = ?",
                String.class,
                created.runId()
        )).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE user_id = ? AND log_type = 'RELEASE'",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE user_id = ? AND source_type = 'WORKFLOW_STEP'",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = ?",
                USER_ID
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);
    }

    @Test
    void oneHundredConcurrentCreatesWithSameKeyProduceOneRootRunAndStepSet() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_concurrent", 10, 5);
        insertCreditAccount(100);
        CreateWorkflowRunCommand command = command(published.toolCode(), "p0-failure-concurrent");
        int callers = 100;
        int workers = 20;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<WorkflowRunCreated>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start timed out");
                    }
                    return runApplicationService.create(command);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<Long> rootTaskIds = new HashSet<>();
            Set<Long> runIds = new HashSet<>();
            for (Future<WorkflowRunCreated> future : futures) {
                WorkflowRunCreated created = future.get(60, TimeUnit.SECONDS);
                rootTaskIds.add(created.rootTaskId());
                runIds.add(created.runId());
            }

            assertThat(rootTaskIds).hasSize(1);
            assertThat(runIds).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                    Integer.class,
                    USER_ID,
                    command.clientRequestId()
            )).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                    Integer.class,
                    USER_ID,
                    command.clientRequestId()
            )).isOne();
            Long onlyRunId = runIds.iterator().next();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_run_steps WHERE run_id = ?",
                    Integer.class,
                    onlyRunId
            )).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_outbox_events WHERE task_id IN (SELECT id FROM ai_tasks WHERE user_id = ?)",
                    Integer.class,
                    USER_ID
            )).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ?",
                    Integer.class,
                    onlyRunId
            )).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sameAgentToolCallDelegationRetriedOneHundredTimesKeepsOneBinding() {
        PublishedWorkflow published = insertInlineWorkflow("p0_failure_agent_delegate");
        AgentFixture agent = insertAgentToolCall(published, "delegate-100");
        Set<Long> taskIds = new HashSet<>();
        Set<Long> runIds = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            DelegatedWorkflowToolCallResponse delegated = delegationService.delegate(agent.toolCallId());
            taskIds.add(delegated.taskId());
            runIds.add(delegated.runId());
        }

        assertThat(taskIds).hasSize(1);
        assertThat(runIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                USER_ID,
                "agent-run-%d-tool-call-%d".formatted(agent.agentRunId(), agent.toolCallId())
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Integer.class,
                USER_ID,
                "agent-run-%d-tool-call-%d".formatted(agent.agentRunId(), agent.toolCallId())
        )).isOne();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, task_id FROM agent_tool_calls WHERE id = ?",
                agent.toolCallId()
        )).containsEntry("status", "SUCCESS")
                .containsEntry("task_id", taskIds.iterator().next());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isOne();
    }

    @Test
    void activeSuccessCallbackRepeatedOneHundredTimesChargesAndPublishesOnce() {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_callback_100", 10, 5);
        insertCreditAccount(100);
        AgentFixture agent = insertAgentToolCall(published, "callback-100");
        DelegatedWorkflowToolCallResponse delegated = delegationService.delegate(agent.toolCallId());
        Long childTaskId = jdbcTemplate.queryForObject(
                "SELECT child_task_id FROM workflow_step_attempts WHERE step_id IN (SELECT id FROM workflow_run_steps WHERE run_id = ?)",
                Long.class,
                delegated.runId()
        );
        assertThat(callbackService.running(childTaskId, LocalDateTime.now().plusMinutes(15))).isTrue();
        WorkerSuccessRequest success = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", 10, 5, 1, "callback-100-claim"
        );

        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (callbackService.succeeded(childTaskId, success)) {
                accepted++;
            }
        }

        assertThat(accepted).isOne();
        assertThat(runStatus(delegated.runId())).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, reserved_credits, charged_credits FROM workflow_step_charges WHERE run_id = ?",
                delegated.runId()
        )).containsEntry("status", "CAPTURED")
                .containsEntry("reserved_credits", 10)
                .containsEntry("charged_credits", 5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE user_id = ? AND source_type = 'WORKFLOW_STEP'",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE user_id = ? AND log_type = 'DEDUCT'",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_tool_calls WHERE id = ?",
                String.class,
                agent.toolCallId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isOne();
    }

    @Test
    void cancelAndSuccessCallbackRaceEndsInOneLegalSettledState() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow("p0_failure_cancel_race", 10, 5);
        insertCreditAccount(100);
        WorkflowRunCreated created = runApplicationService.create(command(
                published.toolCode(),
                "p0-failure-cancel-race"
        ));
        Long childTaskId = jdbcTemplate.queryForObject(
                "SELECT child_task_id FROM workflow_step_attempts WHERE step_id IN (SELECT id FROM workflow_run_steps WHERE run_id = ?)",
                Long.class,
                created.runId()
        );
        assertThat(callbackService.running(childTaskId, LocalDateTime.now().plusMinutes(15))).isTrue();
        WorkerSuccessRequest success = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", 10, 5, 1, "cancel-race-claim"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancel = executor.submit(() -> {
                ready.countDown();
                await(start);
                interactionService.cancel(created.rootTaskId(), USER_ID, "USER_CANCELLED");
            });
            Future<Boolean> callback = executor.submit(() -> {
                ready.countDown();
                await(start);
                return callbackService.succeeded(childTaskId, success);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancel.get(30, TimeUnit.SECONDS);
            callback.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        String runStatus = runStatus(created.runId());
        assertThat(runStatus).isIn("SUCCESS", "CANCELLED");
        String chargeStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE run_id = ?",
                String.class,
                created.runId()
        );
        assertThat(chargeStatus).isEqualTo("SUCCESS".equals(runStatus) ? "CAPTURED" : "RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ? AND status = 'RESERVED'",
                Integer.class,
                created.runId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE user_id = ? AND source_type = 'WORKFLOW_STEP'",
                Integer.class,
                USER_ID
        )).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "RELEASED"})
    void anyLostAttemptClosesAdmissionGateWithoutMutatingItsCharge(String chargeStatus) {
        PublishedWorkflow published = insertInlineWorkflow("p0_failure_lost_" + chargeStatus.toLowerCase());
        LostFixture lost = insertLostAttemptWithBalancedLedger(published, chargeStatus);
        int creditLogCountBefore = countCreditLogs(lost.chargeKey());

        var result = chargeReconciler.reconcileRun(lost.runId());

        assertThat(result.invalidCreditTransitions()).isZero();
        assertThat(result.lostAttempts()).isOne();
        assertThat(result.consistent()).isFalse();
        assertThat(runtimeGate.isReconciliationHealthy()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE idempotency_key = ?",
                String.class,
                lost.chargeKey()
        )).isEqualTo(chargeStatus);
        assertThat(countCreditLogs(lost.chargeKey())).isEqualTo(creditLogCountBefore);
        assertBlocked(
                () -> admissionService.admitNewRun(USER_ID, published.toolId()),
                "reconciliation_not_healthy"
        );
    }

    private PublishedWorkflow insertWorkerWorkflow(String toolCode, int maxCreditCost, int fallbackCredits) {
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                  "parameters":{"maxCreditCost":%d}}}]
                """.formatted(maxCreditCost);
        String billingPolicy = """
                {"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{
                  "maxCreditCost":%d,
                  "maxProviderCostCny":0.10,
                  "fallbackChargeCredits":%d,
                  "staticParams":{},
                  "modelPricingSnapshot":null,
                  "pricingPolicy":{"markupRatio":1.0,"minCredits":0,
                    "imageEstimateInputTokens":8000,"imageEstimateOutputTokens":8000,"rules":[]}
                }}}
                """.formatted(maxCreditCost, fallbackCredits);
        return insertWorkflow(toolCode, nodes, billingPolicy);
    }

    private PublishedWorkflow insertInlineWorkflow(String toolCode) {
        String nodes = "[{\"id\":\"start\",\"data\":{\"nodeDefType\":\"start\",\"title\":\"Start\"}}]";
        return insertWorkflow(
                toolCode,
                nodes,
                "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}"
        );
    }

    private PublishedWorkflow insertConfirmationWorkflow(String toolCode) {
        String nodes = "[{\"id\":\"approval\",\"data\":{\"nodeDefType\":\"user_confirm\",\"title\":\"Approval\"}}]";
        return insertWorkflow(
                toolCode,
                nodes,
                "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}"
        );
    }

    private PublishedWorkflow insertWorkflow(String toolCode, String nodes, String billingPolicy) {
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, ?, 1, 'test', 'ONLINE', 0, 'TEXT_GENERATION',
                          'WORKFLOW', 'WORKFLOW_STEP', 1, 0, 0)
                """, toolCode, toolCode);
        Long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, toolCode
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, 'default', ?, '[]', '{}', 1, 'PUBLISHED', 1, 1,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, nodes);
        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = 'default'",
                Long.class,
                toolId
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, 1, ?, '[]', '{}', '{}', '1', 'p0', ?, '{}', '{}', ?,
                          '{}', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, nodes, "p0-failure-" + workflowId, billingPolicy);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ? AND version = 1",
                Long.class,
                workflowId
        );
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                versionId,
                workflowId
        );
        return new PublishedWorkflow(toolId, workflowId, versionId, toolCode);
    }

    private void insertCapturedUsage(String key, int credits, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                  idempotency_key, source_type, source_id, user_id,
                  charged_credits, customer_charge_credits,
                  cost_amount, vendor_cost_amount, provider_cost_currency,
                  provider_charged, created_at
                ) VALUES (?, 'WORKFLOW_STEP', ?, ?, ?, ?, 0, 0, 'CNY', 1, ?)
                """, key, Math.abs((long) key.hashCode()), USER_ID, credits, credits, createdAt);
        Long usageId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing_usage_logs WHERE idempotency_key = ?",
                Long.class,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_charges(
                  run_id, step_id, user_id, status, reserved_credits, charged_credits,
                  idempotency_key, billing_usage_id, provider_cost, provider_cost_currency,
                  created_at, updated_at
                ) VALUES (?, ?, ?, 'CAPTURED', ?, ?, ?, ?, 0, 'CNY', ?, ?)
                """, usageId, usageId, USER_ID, credits, credits, key + "-charge", usageId,
                createdAt, createdAt);
    }

    private void insertProviderUsage(String key, String cost, LocalDateTime createdAt) {
        insertProviderUsage(key, cost, "CNY", createdAt);
    }

    private void insertProviderUsage(String key, String cost, String currency, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                  idempotency_key, source_type, source_id, user_id,
                  cost_amount, vendor_cost_amount, provider_cost_currency,
                  provider_charged, created_at
                ) VALUES (?, 'WORKFLOW_STEP', ?, ?, ?, ?, ?, 1, ?)
                """, key, Math.abs((long) key.hashCode()), USER_ID, cost, cost, currency, createdAt);
    }

    private LostFixture insertLostAttemptWithBalancedLedger(PublishedWorkflow published,
                                                              String chargeStatus) {
        String requestId = "p0-lost-" + chargeStatus.toLowerCase();
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, params_json,
                  idempotency_key, estimated_credit_cost, queued_at, started_at
                ) VALUES (?, ?, ?, 'PROCESSING', 50, '{}', ?, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "P0-LOST-" + chargeStatus, USER_ID, published.toolId(), requestId);
        Long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Long.class,
                USER_ID,
                requestId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, workflow_version_id,
                  root_task_id, launch_source, client_request_id, status, revision,
                  cancellation_generation, input_json, context_json, billing_status,
                  started_at, created_at, updated_at
                ) VALUES (?, ?, ?, 1, ?, ?, 'AGENTS_PAGE', ?, 'CANCELLING', 0,
                          1, '{}', '{}', 'CLEAR', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, USER_ID, published.toolId(), published.workflowId(), published.versionId(),
                rootTaskId, requestId);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Long.class,
                USER_ID,
                requestId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision,
                  attempt, attempt_count, max_attempts, started_at
                ) VALUES (?, 'lost-worker', 1, 'LLM_TEXT', 'RUNNING', 0, 1, 1, 2,
                          CURRENT_TIMESTAMP)
                """, runId);
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'lost-worker'",
                Long.class,
                runId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_attempts(
                  step_id, attempt_no, cancellation_generation, status, claim_token,
                  created_at, updated_at
                ) VALUES (?, 1, 0, 'LOST', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, stepId, requestId + "-claim");
        Long attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_step_attempts WHERE step_id = ? AND attempt_no = 1",
                Long.class,
                stepId
        );
        String chargeKey = requestId + "-charge";
        jdbcTemplate.update("""
                INSERT INTO workflow_step_charges(
                  run_id, step_id, attempt_id, user_id, status,
                  reserved_credits, charged_credits, idempotency_key
                ) VALUES (?, ?, ?, ?, ?, 10, 0, ?)
                """, runId, stepId, attemptId, USER_ID, chargeStatus, chargeKey);
        jdbcTemplate.update("""
                INSERT INTO credit_logs(
                  user_id, account_id, source_type, source_ref, log_type,
                  amount, frozen_amount, balance_before, balance_after,
                  frozen_before, frozen_after, idempotency_key
                ) VALUES (?, 1, 'WORKFLOW_STEP', ?, 'FREEZE', 0, 10,
                          100, 100, 0, 10, ?)
                """, USER_ID, stepId, chargeKey + ":reserve");
        if ("RELEASED".equals(chargeStatus)) {
            jdbcTemplate.update("""
                    INSERT INTO credit_logs(
                      user_id, account_id, source_type, source_ref, log_type,
                      amount, frozen_amount, balance_before, balance_after,
                      frozen_before, frozen_after, idempotency_key
                    ) VALUES (?, 1, 'WORKFLOW_STEP', ?, 'RELEASE', 0, -10,
                              100, 100, 10, 0, ?)
                    """, USER_ID, stepId, chargeKey + ":release");
        }
        return new LostFixture(runId, chargeKey);
    }

    private AgentFixture insertAgentToolCall(PublishedWorkflow published, String suffix) {
        String key = "p0-agent-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO agent_sessions(user_id, title, status) VALUES (?, ?, 'ACTIVE')",
                USER_ID,
                key
        );
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_sessions WHERE user_id = ? AND title = ?",
                Long.class,
                USER_ID,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO agent_runs(
                  session_id, user_id, status, client_request_id,
                  started_at, created_at, updated_at
                ) VALUES (?, ?, 'RUNNING', ?, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, sessionId, USER_ID, key);
        Long agentRunId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_runs WHERE user_id = ? AND client_request_id = ?",
                Long.class,
                USER_ID,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO agent_tool_calls(
                  run_id, user_id, tool_code, status, arguments_json,
                  started_at, created_at
                ) VALUES (?, ?, ?, 'RUNNING', '{"prompt":"agent workflow"}',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, agentRunId, USER_ID, published.toolCode());
        Long toolCallId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_tool_calls WHERE run_id = ? AND tool_code = ?",
                Long.class,
                agentRunId,
                published.toolCode()
        );
        return new AgentFixture(agentRunId, toolCallId);
    }

    private CreateWorkflowRunCommand command(String toolCode, String requestId) throws Exception {
        return new CreateWorkflowRunCommand(
                USER_ID,
                toolCode,
                objectMapper.readTree("{\"prompt\":\"failure injection\"}"),
                requestId,
                "AGENTS_PAGE",
                null
        );
    }

    private void insertCreditAccount(int balance) {
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (?, ?, 0, ?, 0, ?, 0, 'ACTIVE')
                """, USER_ID, balance, balance, balance);
    }

    private String runStatus(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId
        );
    }

    private String rootStatus(Long rootTaskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?", String.class, rootTaskId
        );
    }

    private int countCreditLogs(String chargeKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key IN (?, ?)",
                Integer.class,
                chargeKey + ":reserve",
                chargeKey + ":release"
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent operation timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent operation interrupted", exception);
        }
    }

    private void assertBlocked(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                               String reason) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_RUNTIME_BLOCKED);
                    assertThat(exception.getData()).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) exception.getData()).get("reason")).isEqualTo(reason);
                });
    }

    private record PublishedWorkflow(long toolId, long workflowId, long versionId, String toolCode) {
    }

    private record LostFixture(long runId, String chargeKey) {
    }

    private record AgentFixture(long agentRunId, long toolCallId) {
    }
}
