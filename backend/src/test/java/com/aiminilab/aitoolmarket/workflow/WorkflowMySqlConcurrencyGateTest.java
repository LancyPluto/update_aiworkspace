package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.agent.dto.DelegatedWorkflowToolCallResponse;
import com.aiminilab.aitoolmarket.agent.balance.VendorBalanceRefreshScheduler;
import com.aiminilab.aitoolmarket.agent.service.AgentRunStaleRecoveryScheduler;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkflowDelegationService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.DataInitializer;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.credit.service.impl.CreditRechargeCreditDispatcher;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.task.TaskOutboxDispatcher;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowAttemptRecoveryService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_GATE_ENABLED", matches = "(?i)true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestPropertySource(properties = {
        "spring.sql.init.mode=never",
        "spring.task.scheduling.enabled=false",
        "app.cache.enabled=false",
        "app.agent.stale-run-recovery-enabled=false",
        "workflow.runtime.enabled=true",
        "workflow.runtime.execution-enabled=true",
        "workflow.runtime.real-billing-enabled=true",
        "workflow.runtime.confirmation-enabled=false",
        "workflow.runtime.canary-percentage=100",
        "workflow.runtime.max-run-cost-credits=100",
        "workflow.runtime.max-user-daily-cost-credits=60",
        "workflow.runtime.max-provider-daily-cost-cny=1000000"
})
class WorkflowMySqlConcurrencyGateTest {

    private static final Pattern ISOLATED_DATABASE_URL = Pattern.compile(
            "^jdbc:mysql://[^/]+/(workflow_p0_gate_[A-Za-z0-9_]+)(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final long USER_ID = 9_900_007_301L;
    private static final long OTHER_USER_ID = 9_900_007_302L;
    private static final String TOOL_PREFIX = "workflow_mysql_gate_";
    private static final int HUNDRED_CALLERS = 100;

    private boolean concurrentWorkersTerminated = true;

    @Autowired
    private WorkflowRunApplicationService runApplicationService;

    @Autowired
    private AgentWorkflowDelegationService delegationService;

    @Autowired
    private InternalTaskService internalTaskService;

    @Autowired
    private WorkflowAttemptRecoveryService attemptRecoveryService;

    @Autowired
    private WorkflowRuntimeGate runtimeGate;

    @Autowired
    private WorkflowRuntimeProperties runtimeProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private DataInitializer dataInitializer;

    @MockBean
    private WorkflowRecoveryScheduler workflowRecoveryScheduler;

    @MockBean
    private TaskOutboxDispatcher taskOutboxDispatcher;

    @MockBean
    private CreditRechargeCreditDispatcher creditRechargeCreditDispatcher;

    @MockBean
    private AgentRunStaleRecoveryScheduler agentRunStaleRecoveryScheduler;

    @MockBean
    private VendorBalanceRefreshScheduler vendorBalanceRefreshScheduler;

    @MockBean
    private CommunityService communityService;

    @DynamicPropertySource
    static void mysqlGateProperties(DynamicPropertyRegistry registry) {
        String url = requiredEnvironment("WORKFLOW_MYSQL_GATE_URL");
        if (!ISOLATED_DATABASE_URL.matcher(url).matches()) {
            throw new IllegalStateException(
                    "WORKFLOW_MYSQL_GATE_URL must target a workflow_p0_gate_ prefixed database"
            );
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnvironment("WORKFLOW_MYSQL_GATE_USERNAME"));
        registry.add("spring.datasource.password", () -> environment("WORKFLOW_MYSQL_GATE_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "110");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
    }

    @BeforeEach
    void setUp() {
        if (!concurrentWorkersTerminated) {
            throw new IllegalStateException("Previous MySQL gate workers are still running");
        }
        cleanupFixtures();
        runtimeGate.markReconciliationHealthyAfterFullScan(
                runtimeGate.reconciliationFailureGeneration()
        );
    }

    @AfterEach
    void tearDown() {
        if (concurrentWorkersTerminated) {
            cleanupFixtures();
        }
    }

    @Test
    void readCommittedHundredConcurrentCreatesKeepSingleWorkflowGraph() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow(TOOL_PREFIX + "same_key", 60, 5);
        insertCreditAccount(1_000);
        String requestId = TOOL_PREFIX + "same_client_request";
        CreateWorkflowRunCommand command = command(published.toolCode(), requestId);

        List<WorkflowRunCreated> results = runConcurrently(
                HUNDRED_CALLERS,
                ignored -> runApplicationService.create(command)
        );

        assertThat(results).extracting(WorkflowRunCreated::rootTaskId).containsOnly(results.get(0).rootTaskId());
        assertThat(results).extracting(WorkflowRunCreated::runId).containsOnly(results.get(0).runId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                USER_ID,
                requestId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Integer.class,
                USER_ID,
                requestId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run_steps WHERE run_id = ?",
                Integer.class,
                results.get(0).runId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ?",
                Integer.class,
                results.get(0).runId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id IN "
                        + "(SELECT id FROM ai_tasks WHERE user_id = ?)",
                Integer.class,
                USER_ID
        )).isOne();
    }

    @Test
    void concurrentDifferentKeysLetOnlyOneRunConsumeTheDailyLimit() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow(TOOL_PREFIX + "daily_limit", 40, 5);
        insertCreditAccount(1_000);

        List<Object> results = runConcurrently(2, index -> {
            try {
                return runApplicationService.create(command(
                        published.toolCode(),
                        TOOL_PREFIX + "daily_request_" + index
                ));
            } catch (BusinessException exception) {
                return exception;
            }
        });

        assertThat(results.stream().filter(WorkflowRunCreated.class::isInstance).count()).isOne();
        assertThat(results.stream().filter(this::isDailyLimitRejection).count()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE user_id = ? AND status = 'RESERVED'",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reserved_credits), 0) FROM workflow_step_charges "
                        + "WHERE user_id = ? AND status = 'RESERVED'",
                Integer.class,
                USER_ID
        )).isEqualTo(40);
    }

    @RepeatedTest(5)
    void concurrentUsersCannotOverbookTheGlobalProviderCostBudget() throws Exception {
        runtimeProperties.setMaxProviderDailyCostCny(new BigDecimal("0.60"));
        PublishedWorkflow published = insertWorkerWorkflow(
                TOOL_PREFIX + "provider_budget",
                40,
                5,
                "0.40"
        );
        insertCreditAccount(USER_ID, 1_000);
        insertCreditAccount(OTHER_USER_ID, 1_000);
        try {
            long[] userIds = {USER_ID, OTHER_USER_ID};
            List<Object> results = runConcurrently(2, index -> {
                try {
                    return runApplicationService.create(command(
                            userIds[index],
                            published.toolCode(),
                            TOOL_PREFIX + "provider_budget_request_" + index
                    ));
                } catch (BusinessException exception) {
                    return exception;
                }
            });

            assertThat(results.stream().filter(WorkflowRunCreated.class::isInstance).count()).isOne();
            assertThat(results.stream().filter(this::isProviderLimitRejection).count()).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(provider_cost_reserved_cny), 0) FROM workflow_runs "
                            + "WHERE user_id IN (?, ?) AND status = 'RUNNING'",
                    BigDecimal.class,
                    USER_ID,
                    OTHER_USER_ID
            )).isEqualByComparingTo("0.400000");
        } finally {
            runtimeProperties.setMaxProviderDailyCostCny(new BigDecimal("1000000"));
        }
    }

    @RepeatedTest(5)
    void callbackAndNewRunUseDeadlockFreeLockOrder() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow(
                TOOL_PREFIX + "callback_create_lock_order",
                20,
                5,
                "0.10"
        );
        insertCreditAccount(1_000);
        WorkflowRunCreated activeRun = runApplicationService.create(command(
                published.toolCode(),
                TOOL_PREFIX + "active_callback_request"
        ));
        Long childTaskId = jdbcTemplate.queryForObject("""
                SELECT attempt.child_task_id
                FROM workflow_step_attempts attempt
                JOIN workflow_run_steps step ON step.id = attempt.step_id
                WHERE step.run_id = ?
                ORDER BY attempt.id DESC
                LIMIT 1
                """, Long.class, activeRun.runId());
        assertThat(childTaskId).isNotNull();
        assertThat(internalTaskService.claim(
                childTaskId,
                new ClaimTaskRequest("mysql-gate-worker", "mysql-gate-claim")
        ).claimed()).isTrue();

        CountDownLatch activeRunLocked = new CountDownLatch(1);
        CountDownLatch createStarted = new CountDownLatch(1);
        WorkerSuccessRequest success = new WorkerSuccessRequest(
                "JSON",
                "{\"result\":\"ok\"}",
                10,
                5,
                1,
                new BigDecimal("0.01"),
                "CNY",
                TOOL_PREFIX + "provider_request",
                "mysql-gate-claim"
        );

        List<Object> results = runConcurrently(2, index -> {
            if (index == 0) {
                TransactionTemplate callbackTransaction = new TransactionTemplate(transactionManager);
                callbackTransaction.setTimeout(60);
                return callbackTransaction.execute(status -> {
                    Long lockedRunId = jdbcTemplate.queryForObject(
                            "SELECT id FROM workflow_runs WHERE id = ? FOR UPDATE",
                            Long.class,
                            activeRun.runId()
                    );
                    assertThat(lockedRunId).isEqualTo(activeRun.runId());
                    activeRunLocked.countDown();
                    await(createStarted, "New workflow run did not start");
                    awaitLockWait("workflow_runs");
                    return internalTaskService.markSuccess(childTaskId, success);
                });
            }
            await(activeRunLocked, "Callback transaction did not lock the active run");
            createStarted.countDown();
            return runApplicationService.create(command(
                    published.toolCode(),
                    TOOL_PREFIX + "concurrent_create_request"
            ));
        });

        assertThat(results.get(0)).isInstanceOf(TaskStatusResponse.class);
        WorkflowRunCreated newRun = (WorkflowRunCreated) results.get(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                activeRun.runId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ? AND status = 'CAPTURED'",
                Integer.class,
                activeRun.runId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ? AND status = 'RESERVED'",
                Integer.class,
                newRun.runId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE user_id = ? AND source_type = 'WORKFLOW_STEP'",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_accounts WHERE user_id = ? AND balance >= frozen AND frozen >= 0",
                Integer.class,
                USER_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM workflow_runs
                WHERE status IN ('RUNNING', 'AWAITING_USER', 'AWAITING_FUNDS', 'CANCELLING')
                  AND (provider_cost_reserved_cny IS NULL OR provider_cost_reserved_cny <= 0)
                """, Integer.class)).isZero();
        BigDecimal providerExposure = jdbcTemplate.queryForObject("""
                SELECT
                  COALESCE((
                    SELECT SUM(vendor_cost_amount)
                    FROM billing_usage_logs
                    WHERE source_type = 'WORKFLOW_STEP'
                      AND provider_charged = 1
                      AND provider_cost_currency = 'CNY'
                  ), 0)
                  + COALESCE((
                    SELECT SUM(provider_cost_reserved_cny)
                    FROM workflow_runs
                    WHERE status IN ('RUNNING', 'AWAITING_USER', 'AWAITING_FUNDS', 'CANCELLING')
                  ), 0)
                """, BigDecimal.class);
        assertThat(providerExposure).isLessThanOrEqualTo(new BigDecimal("1000000"));
    }

    @RepeatedTest(5)
    void dispatchedRecoveryAndNewRunUseDeadlockFreeLockOrder() throws Exception {
        PublishedWorkflow published = insertWorkerWorkflow(
                TOOL_PREFIX + "recovery_create_lock_order",
                20,
                5,
                "0.10"
        );
        insertCreditAccount(1_000);
        WorkflowRunCreated activeRun = runApplicationService.create(command(
                published.toolCode(),
                TOOL_PREFIX + "active_recovery_request"
        ));
        Map<String, Object> recoveryTarget = jdbcTemplate.queryForMap("""
                SELECT step.id AS step_id, attempt.id AS attempt_id
                FROM workflow_run_steps step
                JOIN workflow_step_attempts attempt ON attempt.step_id = step.id
                WHERE step.run_id = ?
                ORDER BY attempt.id DESC
                LIMIT 1
                """, activeRun.runId());
        long stepId = ((Number) recoveryTarget.get("step_id")).longValue();
        long attemptId = ((Number) recoveryTarget.get("attempt_id")).longValue();
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update("UPDATE workflow_run_steps SET max_attempts = 1 WHERE id = ?", stepId);
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                attemptId
        );

        CountDownLatch stepLocked = new CountDownLatch(1);
        CountDownLatch startCreate = new CountDownLatch(1);
        CountDownLatch releaseStepLock = new CountDownLatch(1);
        List<Object> results = runConcurrently(4, index -> {
            if (index == 0) {
                TransactionTemplate blocker = new TransactionTemplate(transactionManager);
                blocker.setTimeout(60);
                return blocker.execute(status -> {
                    Long lockedStepId = jdbcTemplate.queryForObject(
                            "SELECT id FROM workflow_run_steps WHERE id = ? FOR UPDATE",
                            Long.class,
                            stepId
                    );
                    assertThat(lockedStepId).isEqualTo(stepId);
                    stepLocked.countDown();
                    await(releaseStepLock, "Recovery lock coordinator did not release the step");
                    return true;
                });
            }
            if (index == 1) {
                await(stepLocked, "Step blocker did not acquire the workflow step lock");
                return attemptRecoveryService.recoverOne(attemptId, cutoff);
            }
            if (index == 2) {
                await(startCreate, "Recovery did not reach the blocked step");
                return runApplicationService.create(command(
                        published.toolCode(),
                        TOOL_PREFIX + "recovery_concurrent_create_request"
                ));
            }
            await(stepLocked, "Step blocker did not acquire the workflow step lock");
            awaitLockWait("workflow_run_steps");
            startCreate.countDown();
            awaitLockWait("workflow_runs");
            releaseStepLock.countDown();
            return true;
        });

        assertThat(results.get(1)).isEqualTo(true);
        WorkflowRunCreated newRun = (WorkflowRunCreated) results.get(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                activeRun.runId()
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_attempts WHERE id = ?",
                String.class,
                attemptId
        )).isEqualTo("TIMEOUT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ? AND status = 'RELEASED'",
                Integer.class,
                activeRun.runId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ? AND status = 'RESERVED'",
                Integer.class,
                newRun.runId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_accounts WHERE user_id = ? AND balance >= frozen AND frozen >= 0",
                Integer.class,
                USER_ID
        )).isOne();
    }

    @Test
    void repeatableReadHundredConcurrentDelegationsKeepSingleToolCallBinding() throws Exception {
        PublishedWorkflow published = insertInlineWorkflow(TOOL_PREFIX + "rr_delegate");
        AgentFixture agent = insertAgentToolCall(published);

        List<DelegatedWorkflowToolCallResponse> results = runConcurrently(
                HUNDRED_CALLERS,
                ignored -> {
                    TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
                    repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    repeatableRead.setTimeout(120);
                    return repeatableRead.execute(status -> {
                        String isolation = jdbcTemplate.queryForObject(
                                "SELECT @@transaction_isolation",
                                String.class
                        );
                        if (!"REPEATABLE-READ".equalsIgnoreCase(isolation)) {
                            throw new IllegalStateException("Expected MySQL REPEATABLE-READ, got " + isolation);
                        }
                        return delegationService.delegate(agent.toolCallId());
                    });
                }
        );

        Set<Long> taskIds = new HashSet<>();
        Set<Long> runIds = new HashSet<>();
        results.forEach(result -> {
            taskIds.add(result.taskId());
            runIds.add(result.runId());
        });
        assertThat(taskIds).hasSize(1);
        assertThat(runIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                USER_ID,
                agent.clientRequestId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Integer.class,
                USER_ID,
                agent.clientRequestId()
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

    private boolean isDailyLimitRejection(Object result) {
        if (!(result instanceof BusinessException exception)
                || exception.getErrorCode() != ErrorCode.WORKFLOW_RUNTIME_BLOCKED
                || !(exception.getData() instanceof Map<?, ?> data)) {
            return false;
        }
        return "user_daily_cost_limit_exceeded".equals(data.get("reason"));
    }

    private boolean isProviderLimitRejection(Object result) {
        if (!(result instanceof BusinessException exception)
                || exception.getErrorCode() != ErrorCode.WORKFLOW_RUNTIME_BLOCKED
                || !(exception.getData() instanceof Map<?, ?> data)) {
            return false;
        }
        return "provider_daily_cost_limit_exceeded".equals(data.get("reason"));
    }

    private PublishedWorkflow insertWorkerWorkflow(String toolCode,
                                                    int maxCreditCost,
                                                    int fallbackCredits) {
        return insertWorkerWorkflow(toolCode, maxCreditCost, fallbackCredits, "0.10");
    }

    private PublishedWorkflow insertWorkerWorkflow(String toolCode,
                                                    int maxCreditCost,
                                                    int fallbackCredits,
                                                    String maxProviderCostCny) {
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                  "parameters":{"maxCreditCost":%d}}}]
                """.formatted(maxCreditCost);
        String billingPolicy = """
                {"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{
                  "maxCreditCost":%d,
                  "maxProviderCostCny":%s,
                  "fallbackChargeCredits":%d,
                  "staticParams":{},
                  "modelPricingSnapshot":null,
                  "pricingPolicy":{"markupRatio":1.0,"minCredits":0,
                    "imageEstimateInputTokens":8000,"imageEstimateOutputTokens":8000,"rules":[]}
                }}}
                """.formatted(maxCreditCost, maxProviderCostCny, fallbackCredits);
        return insertWorkflow(toolCode, nodes, billingPolicy);
    }

    private PublishedWorkflow insertInlineWorkflow(String toolCode) {
        return insertWorkflow(
                toolCode,
                "[{\"id\":\"start\",\"data\":{\"nodeDefType\":\"start\",\"title\":\"Start\"}}]",
                "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}"
        );
    }

    private PublishedWorkflow insertWorkflow(String toolCode, String nodes, String billingPolicy) {
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, ?, 1, 'mysql gate', 'ONLINE', 0, 'TEXT_GENERATION',
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
                """, workflowId, nodes, TOOL_PREFIX + "hash_" + workflowId, billingPolicy);
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

    private AgentFixture insertAgentToolCall(PublishedWorkflow published) {
        String key = TOOL_PREFIX + "agent_session";
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
                ) VALUES (?, ?, ?, 'RUNNING', '{"prompt":"mysql gate"}',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, agentRunId, USER_ID, published.toolCode());
        Long toolCallId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_tool_calls WHERE run_id = ? AND tool_code = ?",
                Long.class,
                agentRunId,
                published.toolCode()
        );
        return new AgentFixture(
                agentRunId,
                toolCallId,
                "agent-run-%d-tool-call-%d".formatted(agentRunId, toolCallId)
        );
    }

    private CreateWorkflowRunCommand command(String toolCode, String requestId) {
        return command(USER_ID, toolCode, requestId);
    }

    private CreateWorkflowRunCommand command(long userId, String toolCode, String requestId) {
        return new CreateWorkflowRunCommand(
                userId,
                toolCode,
                objectMapper.createObjectNode().put("prompt", "mysql concurrency gate"),
                requestId,
                "AGENTS_PAGE",
                null
        );
    }

    private void insertCreditAccount(int balance) {
        insertCreditAccount(USER_ID, balance);
    }

    private void insertCreditAccount(long userId, int balance) {
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (?, ?, 0, ?, 0, ?, 0, 'ACTIVE')
                """, userId, balance, balance, balance);
    }

    private void cleanupFixtures() {
        cleanupWorkflowUser(OTHER_USER_ID);
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_tool_calls WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_sessions WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_confirmations WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_step_charges WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_step_attempts WHERE step_id IN "
                + "(SELECT id FROM workflow_run_steps WHERE run_id IN "
                + "(SELECT id FROM workflow_runs WHERE user_id = ?))", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_run_steps WHERE run_id IN "
                + "(SELECT id FROM workflow_runs WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_runs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM task_outbox_events WHERE task_id IN "
                + "(SELECT id FROM ai_tasks WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM billing_usage_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM ai_tasks WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tool_workflow_versions WHERE workflow_id IN "
                + "(SELECT id FROM tool_workflows WHERE tool_id IN "
                + "(SELECT id FROM ai_tools WHERE LEFT(tool_code, CHAR_LENGTH(?)) = ?))",
                TOOL_PREFIX, TOOL_PREFIX);
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE tool_id IN "
                + "(SELECT id FROM ai_tools WHERE LEFT(tool_code, CHAR_LENGTH(?)) = ?)",
                TOOL_PREFIX, TOOL_PREFIX);
        jdbcTemplate.update("DELETE FROM ai_tools WHERE LEFT(tool_code, CHAR_LENGTH(?)) = ?",
                TOOL_PREFIX, TOOL_PREFIX);
    }

    private void cleanupWorkflowUser(long userId) {
        jdbcTemplate.update("DELETE FROM workflow_confirmations WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM workflow_step_charges WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM workflow_step_attempts WHERE step_id IN "
                + "(SELECT id FROM workflow_run_steps WHERE run_id IN "
                + "(SELECT id FROM workflow_runs WHERE user_id = ?))", userId);
        jdbcTemplate.update("DELETE FROM workflow_run_steps WHERE run_id IN "
                + "(SELECT id FROM workflow_runs WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM workflow_runs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM task_outbox_events WHERE task_id IN "
                + "(SELECT id FROM ai_tasks WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM billing_usage_logs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM ai_tasks WHERE user_id = ?", userId);
    }

    private <T> List<T> runConcurrently(int callers, ConcurrentOperation<T> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<T>> futures = new ArrayList<>(callers);
        concurrentWorkersTerminated = false;
        try {
            for (int index = 0; index < callers; index++) {
                int callerIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent MySQL gate start timed out");
                    }
                    return operation.call(callerIndex);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            List<T> results = new ArrayList<>(callers);
            for (Future<T> future : futures) {
                long remaining = Math.max(1L, deadline - System.nanoTime());
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.stream()
                    .filter(future -> !future.isDone())
                    .forEach(future -> future.cancel(true));
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent MySQL gate workers did not terminate");
                }
            } finally {
                concurrentWorkersTerminated = executor.isTerminated();
            }
        }
    }

    private void awaitLockWait(String tableName) {
        awaitAnyLockWait(tableName);
    }

    private void awaitAnyLockWait(String... tableNames) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (String tableName : tableNames) {
                Integer waits = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks requested
                          ON requested.engine_lock_id = waits.requesting_engine_lock_id
                        WHERE requested.object_schema = DATABASE()
                          AND requested.object_name = ?
                        """, Integer.class, tableName);
                if (waits != null && waits > 0) {
                    return;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new IllegalStateException(
                "Expected a MySQL lock wait on one of " + java.util.Arrays.toString(tableNames)
        );
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = environment(name);
        if (value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException(name + " is required when the MySQL gate is enabled");
        }
        return value;
    }

    @FunctionalInterface
    private interface ConcurrentOperation<T> {
        T call(int index) throws Exception;
    }

    private record PublishedWorkflow(long toolId, long workflowId, long versionId, String toolCode) {
    }

    private record AgentFixture(long agentRunId, long toolCallId, String clientRequestId) {
    }
}
