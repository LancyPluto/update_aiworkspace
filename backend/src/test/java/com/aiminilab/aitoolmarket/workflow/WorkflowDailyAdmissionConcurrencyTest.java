package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_daily_admission_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "workflow.runtime.enabled=true",
        "workflow.runtime.execution-enabled=true",
        "workflow.runtime.real-billing-enabled=true",
        "workflow.runtime.confirmation-enabled=false",
        "spring.task.scheduling.enabled=false"
})
class WorkflowDailyAdmissionConcurrencyTest {

    private static final long USER_ID = 7301L;
    private static final long OTHER_USER_ID = 7302L;
    private static final String TOOL_CODE = "daily_admission_concurrency";
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{},"required":[]}
            """;

    @Autowired
    private WorkflowRunApplicationService runApplicationService;

    @Autowired
    private WorkflowRuntimeGate runtimeGate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowRecoveryScheduler workflowRecoveryScheduler;

    @BeforeEach
    void setUp() {
        cleanupUser(OTHER_USER_ID);
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
                + "(SELECT id FROM ai_tools WHERE tool_code = ?))", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE tool_id IN "
                + "(SELECT id FROM ai_tools WHERE tool_code = ?)", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code = ?", TOOL_CODE);

        insertPaidWorkflow();
        insertCreditAccount(USER_ID);
        insertCreditAccount(OTHER_USER_ID);
        runtimeGate.markReconciliationHealthyAfterFullScan(
                runtimeGate.reconciliationFailureGeneration()
        );
    }

    private void insertCreditAccount(long userId) {
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (?, 100, 100, 0, 0, 100, 0, 'ACTIVE')
                """, userId);
    }

    @Test
    void concurrentRunsProceedWhenTheAccountCanCoverBothReservations() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                String requestId = "daily-admission-" + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start timed out");
                    }
                    try {
                        return runApplicationService.create(command(requestId));
                    } catch (BusinessException exception) {
                        return exception;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(WorkflowRunCreated.class::isInstance).count()).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reserved_credits), 0) FROM workflow_step_charges "
                        + "WHERE user_id = ? AND status = 'RESERVED'",
                Integer.class,
                USER_ID
        )).isEqualTo(80);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isEqualTo(80);
    }

    @Test
    void concurrentUsersAreNotGloballyLimitedByOptionalProviderCost() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            long[] userIds = {USER_ID, OTHER_USER_ID};
            for (int index = 0; index < userIds.length; index++) {
                long userId = userIds[index];
                String requestId = "provider-budget-" + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start timed out");
                    }
                    try {
                        return runApplicationService.create(command(userId, requestId));
                    } catch (BusinessException exception) {
                        return exception;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(WorkflowRunCreated.class::isInstance).count()).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id IN (?, ?)",
                Integer.class,
                USER_ID,
                OTHER_USER_ID
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(provider_cost_reserved_cny), 0) FROM workflow_runs "
                        + "WHERE user_id IN (?, ?) AND status = 'RUNNING'",
                BigDecimal.class,
                USER_ID,
                OTHER_USER_ID
        )).isEqualByComparingTo("0.000000");
    }

    private CreateWorkflowRunCommand command(String requestId) {
        return command(USER_ID, requestId);
    }

    private CreateWorkflowRunCommand command(long userId, String requestId) {
        return new CreateWorkflowRunCommand(
                userId,
                TOOL_CODE,
                objectMapper.createObjectNode().put("prompt", "daily admission"),
                requestId,
                "AGENTS_PAGE",
                null
        );
    }

    private void cleanupUser(long userId) {
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

    private void insertPaidWorkflow() {
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                  "parameters":{"maxCreditCost":40}}}]
                """;
        String billingPolicy = """
                {"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{
                  "maxCreditCost":40,
                  "estimatedProviderCostCny":0,
                  "maxProviderCostCny":0,
                  "fallbackChargeCredits":5,
                  "pricingSource":"TOOL_FALLBACK",
                  "staticParams":{},
                  "modelPricingSnapshot":null,
                  "pricingPolicy":{"markupRatio":1.0,"minCredits":0,
                    "imageEstimateInputTokens":8000,"imageEstimateOutputTokens":8000,"rules":[]}
                }}}
                """;
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, 'Daily Admission Test', 1, 'test', 'ONLINE', 0,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, 0, 0)
                """, TOOL_CODE);
        Long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, TOOL_CODE
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
                ) VALUES (?, 1, ?, '[]', '{}', '{}', '1', 'p0', ?, ?, '{}', ?,
                          '{}', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, nodes, "daily-admission-" + workflowId, INPUT_SCHEMA, billingPolicy);
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
    }
}
