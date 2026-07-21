package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import io.micrometer.core.instrument.MeterRegistry;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowChargeReconciler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowCancellationService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowInteractionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_late_callback_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "workflow.runtime.auto-retry-enabled=true"
})
class WorkflowLateCallbackTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowStepScheduler scheduler;

    @Autowired
    private WorkflowStepCallbackService callbacks;

    @Autowired
    private InternalTaskService internalTaskService;

    @Autowired
    private WorkflowRunStepMapper stepMapper;

    @Autowired
    private WorkflowStepAttemptMapper attemptMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowChargeReconciler chargeReconciler;

    @Autowired
    private WorkflowInteractionService interactionService;

    @Autowired
    private WorkflowCancellationService cancellationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private WorkflowExecutionService executionService;

    private long stepId;

    @BeforeEach
    void setUp() {
        stepId = insertWorkflow("workflow_callback_test", 2);
    }

    @Test
    void retryCreatesNewAttemptAndLateFirstSuccessCannotAdvanceStep() {
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        claim(first);
        callbacks.failed(first.getChildTaskId(), failure("MODEL_TIMEOUT", "timeout"));
        WorkflowRunStep retriedStep = stepMapper.selectById(stepId);
        WorkflowStepAttempt second = attemptMapper.selectById(retriedStep.getCurrentAttemptId());

        assertThat(callbacks.succeeded(first.getChildTaskId(), success("{\"late\":true}"))).isFalse();

        WorkflowRunStep step = stepMapper.selectById(stepId);
        assertThat(second).isNotNull();
        assertThat(second.getAttemptNo()).isEqualTo(2);
        assertThat(step.getCurrentAttemptId()).isEqualTo(second.getId());
        assertThat(step.getStatus()).isEqualTo("QUEUED");
        assertThat(attemptMapper.selectById(first.getId()).getStatus()).isEqualTo("FAILED");
        verify(executionService, never()).onStepAttemptSucceeded(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(WorkerSuccessRequest.class)
        );
    }

    @Test
    void lateFirstFailureCannotFailRetriedStep() {
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        claim(first);
        WorkerFailedRequest firstFailure = failure("MODEL_TIMEOUT", "timeout");
        callbacks.failed(first.getChildTaskId(), firstFailure);
        WorkflowRunStep retriedStep = stepMapper.selectById(stepId);
        WorkflowStepAttempt second = attemptMapper.selectById(retriedStep.getCurrentAttemptId());

        assertThat(callbacks.failed(first.getChildTaskId(), firstFailure)).isFalse();

        WorkflowRunStep step = stepMapper.selectById(stepId);
        assertThat(second).isNotNull();
        assertThat(second.getAttemptNo()).isEqualTo(2);
        assertThat(step.getCurrentAttemptId()).isEqualTo(second.getId());
        assertThat(step.getStatus()).isEqualTo("QUEUED");
        verify(executionService, never()).onStepAttemptsExhausted(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(WorkerFailedRequest.class)
        );
    }

    @Test
    void duplicateActiveSuccessOnlyCompletesStepOnce() {
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        jdbcTemplate.update("""
                UPDATE ai_tools SET is_deleted = 1, status = 'OFFLINE'
                WHERE id = (
                  SELECT r.tool_id FROM workflow_runs r
                  JOIN workflow_run_steps s ON s.run_id = r.id
                  WHERE s.id = ?
                )
                """, stepId);
        WorkerSuccessRequest request = success("{\"value\":42}");

        assertThat(callbacks.succeeded(active.getChildTaskId(), request)).isTrue();
        assertThat(callbacks.succeeded(active.getChildTaskId(), request)).isFalse();

        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("SUCCESS");
        assertThat(attemptMapper.selectById(active.getId()).getStatus()).isEqualTo("SUCCESS");
        verify(executionService, times(1)).onStepAttemptSucceeded(stepId, active.getChildTaskId(), request);
    }

    @Test
    void finalActiveFailureFailsStepAndRunOnce() {
        stepId = insertWorkflow("workflow_callback_exhausted_test", 1);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerFailedRequest request = failure("MODEL_CALL_FAILED", "fatal");

        assertThat(callbacks.failed(active.getChildTaskId(), request)).isTrue();
        assertThat(callbacks.failed(active.getChildTaskId(), request)).isFalse();

        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("FAILED");
        assertThat(attemptMapper.selectById(active.getId()).getStatus()).isEqualTo("FAILED");
        verify(executionService, times(1)).onStepAttemptsExhausted(stepId, request);
    }

    @Test
    void paidSuccessCapturesActualCreditsAndWritesUsageOnce() {
        double reservedBefore = billingMetric("reserved");
        double capturedBefore = billingMetric("captured");
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = success("{\"value\":42}");

        assertThat(callbacks.succeeded(active.getChildTaskId(), request)).isTrue();
        assertThat(callbacks.succeeded(active.getChildTaskId(), request)).isFalse();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, reserved_credits, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "CAPTURED")
                .containsEntry("reserved_credits", 20)
                .containsEntry("charged_credits", 18);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 82)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 18);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
        long runId = jdbcTemplate.queryForObject(
                "SELECT run_id FROM workflow_run_steps WHERE id = ?", Long.class, stepId
        );
        assertThat(chargeReconciler.reconcileRun(runId))
                .extracting("capturedCredits", "usageCredits", "creditLogDeductions", "consistent")
                .containsExactly(18, 18, 18, true);
        assertThat(billingMetric("reserved") - reservedBefore).isEqualTo(1);
        assertThat(billingMetric("captured") - capturedBefore).isEqualTo(1);
    }

    @Test
    void pricingSnapshotRuleSurvivesLivePricingChangesAndToolDeletion() {
        configureSnapshotPricedStep(40, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        attachPerCallModelSnapshot(active.getChildTaskId(), "9.00");
        long toolId = toolIdForStep();
        try {
            jdbcTemplate.update("""
                    INSERT INTO pricing_margins(
                      scope_type, scope_ref, markup_ratio, min_credits, enabled, remark
                    ) VALUES ('TOOL', ?, 9.0, 0, 1, 'changed after dispatch')
                    """, toolId);
            jdbcTemplate.update("""
                    INSERT INTO pricing_rules(
                      scope_type, scope_ref, param_key, rule_type, match_op, match_value,
                      factor, extra_credits, priority, enabled, remark
                    ) VALUES ('TOOL', ?, 'quality', 'MULTIPLIER', 'EQ', 'hd',
                              9.0, 0, 1, 1, 'changed after dispatch')
                    """, toolId);
            jdbcTemplate.update("UPDATE ai_tools SET is_deleted = 1, status = 'OFFLINE' WHERE id = ?", toolId);

            assertThat(callbacks.succeeded(
                    active.getChildTaskId(),
                    successWithProviderCost("{\"value\":42}", "0.20")
            )).isTrue();

            assertThat(jdbcTemplate.queryForMap(
                    "SELECT status, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                    active.getId()
            )).containsEntry("status", "CAPTURED")
                    .containsEntry("charged_credits", 30);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT provider_cost FROM workflow_step_charges WHERE attempt_id = ?",
                    BigDecimal.class,
                    active.getId()
            )).isEqualByComparingTo("0.20");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT markup_ratio FROM billing_usage_logs WHERE idempotency_key = ?",
                    BigDecimal.class,
                    active.getClaimToken() + ":usage"
            )).isEqualByComparingTo("1.5");
        } finally {
            jdbcTemplate.update("DELETE FROM pricing_rules WHERE scope_type = 'TOOL' AND scope_ref = ?", toolId);
            jdbcTemplate.update("DELETE FROM pricing_margins WHERE scope_type = 'TOOL' AND scope_ref = ?", toolId);
        }
    }

    @Test
    void quoteAboveReservationCapCapturesReservationAndDeductsShortfall() {
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        attachPerCallModelSnapshot(active.getChildTaskId(), "9.00");

        assertThat(callbacks.succeeded(
                active.getChildTaskId(),
                successWithProviderCost("{\"value\":42}", "0.20")
        )).isTrue();

        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, reserved_credits, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "CAPTURED")
                .containsEntry("reserved_credits", 20)
                .containsEntry("charged_credits", 30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_cost FROM workflow_step_charges WHERE attempt_id = ?",
                BigDecimal.class,
                active.getId()
        )).isEqualByComparingTo("0.20");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 70)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 30);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT vendor_cost_amount, charged_credits FROM billing_usage_logs WHERE idempotency_key = ?",
                active.getClaimToken() + ":usage"
        )).containsEntry("charged_credits", 30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT vendor_cost_amount FROM billing_usage_logs WHERE idempotency_key = ?",
                BigDecimal.class,
                active.getClaimToken() + ":usage"
        )).isEqualByComparingTo("0.20");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_price FROM billing_usage_logs WHERE idempotency_key = ?",
                BigDecimal.class,
                active.getClaimToken() + ":usage"
        )).isEqualByComparingTo("0.10");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":shortfall"
        )).isEqualTo(1);
    }

    @Test
    void insufficientShortfallPersistsProviderSuccessAndResumesSettlementAfterRecharge() {
        configureSnapshotPricedStep(20, 25);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        attachPerCallModelSnapshot(active.getChildTaskId(), "9.00");
        WorkerSuccessRequest request = successWithProviderCost("{\"value\":42}", "0.20");
        long runId = runIdForStep();
        long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT root_task_id FROM workflow_runs WHERE id = ?", Long.class, runId
        );
        jdbcTemplate.update(
                "UPDATE ai_tasks SET provider_checkpoint_json = '{\"status\":\"COMPLETED\"}' WHERE id = ?",
                active.getChildTaskId()
        );

        internalTaskService.markSuccess(active.getChildTaskId(), request);
        internalTaskService.markSuccess(active.getChildTaskId(), request);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits, settlement_payload_json FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "AWAITING_FUNDS")
                .containsEntry("charged_credits", 0)
                .satisfies(row -> assertThat((String) row.get("settlement_payload_json"))
                        .contains("\"billableUnits\":1")
                        .contains("\"providerCalled\":true"));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 25)
                .containsEntry("frozen", 20)
                .containsEntry("total_consumed", 0);
        assertThat(attemptMapper.selectById(active.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(attemptMapper.selectById(active.getId()).getOutputJson()).contains("\"value\":42");
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("AWAITING_FUNDS");
        assertThat(stepMapper.selectById(stepId).getOutputJson()).contains("\"value\":42");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId
        )).isEqualTo("AWAITING_FUNDS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?", String.class, rootTaskId
        )).isEqualTo("AWAITING_FUNDS");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, provider_checkpoint_json FROM ai_tasks WHERE id = ?",
                active.getChildTaskId()
        )).containsEntry("status", "SUCCESS")
                .containsEntry("provider_checkpoint_json", "{\"status\":\"COMPLETED\"}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key IN (?, ?, ?)",
                Integer.class,
                active.getClaimToken() + ":capture",
                active.getClaimToken() + ":shortfall",
                active.getClaimToken() + ":shortfall-reserve"
        )).isZero();
        assertThat(chargeReconciler.reconcileRun(runId))
                .extracting("reservedCharges", "invalidCreditTransitions", "consistent")
                .containsExactly(1, 0, true);
        verify(executionService, never()).onStepAttemptSucceeded(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(WorkerSuccessRequest.class)
        );

        jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance + 10,
                    membership_balance = membership_balance + 10,
                    total_granted = total_granted + 10
                WHERE user_id = 1
                """);

        interactionService.resume(rootTaskId, 1L);
        interactionService.resume(rootTaskId, 1L);
        internalTaskService.markSuccess(active.getChildTaskId(), request);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "CAPTURED")
                .containsEntry("charged_credits", 30);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 5)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 30);
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?",
                Integer.class,
                stepId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, provider_checkpoint_json FROM ai_tasks WHERE id = ?",
                active.getChildTaskId()
        )).containsEntry("status", "SUCCESS")
                .containsEntry("provider_checkpoint_json", "{\"status\":\"COMPLETED\"}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId
        )).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?", String.class, rootTaskId
        )).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key IN (?, ?, ?)",
                Integer.class,
                active.getClaimToken() + ":capture",
                active.getClaimToken() + ":shortfall",
                active.getClaimToken() + ":shortfall-reserve"
        )).isEqualTo(3);
        assertThat(chargeReconciler.reconcileRun(runId))
                .extracting("capturedCredits", "usageCredits", "creditLogDeductions", "consistent")
                .containsExactly(30, 30, 30, true);
        verify(executionService, times(1)).onStepAttemptSucceeded(
                org.mockito.ArgumentMatchers.eq(stepId),
                org.mockito.ArgumentMatchers.eq(active.getChildTaskId()),
                org.mockito.ArgumentMatchers.any(WorkerSuccessRequest.class)
        );
    }

    @Test
    void cancellingDeferredSettlementReleasesReservationAndRecordsProviderUsage() {
        configureSnapshotPricedStep(20, 25);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = successWithProviderCost("{\"value\":42}", "0.20");
        long runId = runIdForStep();
        long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT root_task_id FROM workflow_runs WHERE id = ?", Long.class, runId
        );
        internalTaskService.markSuccess(active.getChildTaskId(), request);

        cancellationService.begin(rootTaskId, 1L, "USER_CANCELLED");
        WorkerSuccessRequest conflictingReplay = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", null, null, null,
                null, null, null, false, "worker-claim"
        );
        assertThatThrownBy(() -> callbacks.succeeded(active.getChildTaskId(), conflictingReplay))
                .hasMessageContaining("conflicts with persisted usage");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, provider_cost, billing_usage_id, settlement_payload_json "
                        + "FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "AWAITING_FUNDS")
                .containsEntry("provider_cost", null)
                .containsEntry("billing_usage_id", null)
                .satisfies(row -> assertThat((String) row.get("settlement_payload_json"))
                        .contains("\"promptTokens\":10")
                        .contains("\"completionTokens\":5")
                        .contains("\"billableUnits\":1")
                        .contains("\"providerCostAmount\":0.20")
                        .contains("\"providerCalled\":true"));
        assertThat(cancellationService.settle(rootTaskId, 1L, "USER_CANCELLED")).isTrue();
        assertThat(callbacks.succeeded(active.getChildTaskId(), request)).isFalse();
        assertThat(cancellationService.settle(rootTaskId, 1L, "USER_CANCELLED")).isTrue();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits, provider_cost FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("charged_credits", 0)
                .containsEntry("provider_cost", new BigDecimal("0.200000"));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 25)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT outcome, charged_credits, vendor_cost_amount, prompt_tokens, "
                        + "completion_tokens, billable_units, provider_charged "
                        + "FROM billing_usage_logs WHERE idempotency_key = ?",
                active.getClaimToken() + ":usage"
        )).containsEntry("outcome", "CANCELLED_LATE_SUCCESS")
                .containsEntry("charged_credits", 0)
                .containsEntry("vendor_cost_amount", new BigDecimal("0.200000"))
                .containsEntry("prompt_tokens", 10)
                .containsEntry("completion_tokens", 5)
                .containsEntry("billable_units", 1)
                .containsEntry("provider_charged", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId
        )).isEqualTo("CANCELLED");
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("CANCELLED");
        assertThat(attemptMapper.selectById(active.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(attemptMapper.selectById(active.getId()).getOutputJson()).contains("\"value\":42");
        assertThat(chargeReconciler.reconcileRun(runId).consistent()).isTrue();
    }

    @Test
    void successWithoutProviderCallReleasesEntireReservation() {
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", null, null, null,
                null, null, null, false, "worker-claim"
        );

        assertThat(callbacks.succeeded(active.getChildTaskId(), request)).isTrue();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits, billing_usage_id FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("charged_credits", 0)
                .containsEntry("billing_usage_id", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);
    }

    @Test
    void providerCalledPerCallSettlementRequiresReportedUnits() {
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", 10, 5, null,
                null, null, null, true, "worker-claim"
        );

        assertThatThrownBy(() -> callbacks.succeeded(active.getChildTaskId(), request))
                .hasMessageContaining("missing actual usage for PER_CALL");

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RESERVED")
                .containsEntry("charged_credits", 0);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 20)
                .containsEntry("total_consumed", 0);
    }

    @Test
    void modelPricingSettlementRejectsMissingProviderCalledDeclaration() {
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", 10, 5, 1,
                new BigDecimal("0.20"), "CNY", null, "worker-claim"
        );

        assertThatThrownBy(() -> callbacks.succeeded(active.getChildTaskId(), request))
                .hasMessageContaining("must declare providerCalled");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                active.getId()
        )).isEqualTo("RESERVED");
    }

    @Test
    void providerNotCalledRejectsContradictoryUsageAndAccounting() {
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", 10, 5, 1,
                new BigDecimal("0.20"), "CNY", "contradictory-provider-request", false, "worker-claim"
        );

        assertThatThrownBy(() -> callbacks.succeeded(active.getChildTaskId(), request))
                .hasMessageContaining("providerCalled=false conflicts");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                active.getId()
        )).isEqualTo("RESERVED");
    }

    @Test
    void modelPricingRejectsMissingModelSnapshotAtSettlement() throws Exception {
        configureSnapshotPricedStep(20, 100);
        ObjectNode nodePolicy = billingNodePolicy();
        nodePolicy.putNull("modelPricingSnapshot");
        updateBillingNodePolicy(nodePolicy);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);

        assertThatThrownBy(() -> callbacks.succeeded(
                active.getChildTaskId(), successWithProviderCost("{\"value\":42}", "0.20")
        )).hasMessageContaining("no valid model snapshot");
    }

    @Test
    void toolFallbackRejectsEmbeddedModelSnapshotAtSettlement() throws Exception {
        configureSnapshotPricedStep(20, 100);
        ObjectNode nodePolicy = billingNodePolicy();
        nodePolicy.put("pricingSource", "TOOL_FALLBACK");
        nodePolicy.put("fallbackChargeCredits", 20);
        updateBillingNodePolicy(nodePolicy);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);

        assertThatThrownBy(() -> callbacks.succeeded(
                active.getChildTaskId(), successWithProviderCost("{\"value\":42}", "0.20")
        )).hasMessageContaining("must have no model and a positive fallback");
    }

    @Test
    void localZeroCostRejectsHandlerOutsideAllowlistAtSettlement() throws Exception {
        configurePaidStep(20, 18, 100);
        ObjectNode nodePolicy = billingNodePolicy();
        nodePolicy.put("pricingSource", "LOCAL_ZERO_COST");
        nodePolicy.put("estimatedProviderCostCny", 0);
        nodePolicy.put("maxProviderCostCny", 0);
        nodePolicy.put("fallbackChargeCredits", 0);
        ((ObjectNode) nodePolicy.path("staticParams")).put("handlerKey", "comic.shot.video");
        updateBillingNodePolicy(nodePolicy);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerSuccessRequest request = new WorkerSuccessRequest(
                "JSON", "{\"value\":42}", null, null, null,
                null, null, null, false, "worker-claim"
        );

        assertThatThrownBy(() -> callbacks.succeeded(active.getChildTaskId(), request))
                .hasMessageContaining("violates its pricing invariant");
    }

    @Test
    void terminalRunWithReservedChargeIsNotReconciledAsConsistent() {
        configurePaidStep(20, 18, 100);
        scheduler.dispatch(stepId);
        long runId = runIdForStep();
        jdbcTemplate.update("UPDATE workflow_runs SET status = 'SUCCESS' WHERE id = ?", runId);

        assertThat(chargeReconciler.reconcileRun(runId))
                .extracting("reservedCharges", "invalidCreditTransitions", "consistent")
                .containsExactly(1, 0, false);
    }

    @Test
    void releasedChargeWithoutReleaseLogIsNotReconciledAsConsistent() {
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        long runId = runIdForStep();
        jdbcTemplate.update(
                "UPDATE workflow_step_charges SET status = 'RELEASED' WHERE attempt_id = ?",
                active.getId()
        );
        jdbcTemplate.update("UPDATE workflow_runs SET status = 'FAILED' WHERE id = ?", runId);

        assertThat(chargeReconciler.reconcileRun(runId))
                .extracting("reservedCharges", "invalidCreditTransitions", "consistent")
                .containsExactly(0, 1, false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = 1", Integer.class
        )).isEqualTo(20);
    }

    @Test
    void paidFailureReleasesReservationExactlyOnce() {
        double reservedBefore = billingMetric("reserved");
        double releasedBefore = billingMetric("released");
        stepId = insertWorkflow("workflow_paid_failure_final_test", 1);
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerFailedRequest request = failure("MODEL_CALL_FAILED", "fatal");

        assertThat(callbacks.failed(active.getChildTaskId(), request)).isTrue();
        assertThat(callbacks.failed(active.getChildTaskId(), request)).isFalse();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, reserved_credits, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("reserved_credits", 20)
                .containsEntry("charged_credits", 0);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);
        assertThat(billingMetric("reserved") - reservedBefore).isEqualTo(1);
        assertThat(billingMetric("released") - releasedBefore).isEqualTo(1);
    }

    @Test
    void paidFailureRecordsProviderCostWithoutChargingUser() {
        stepId = insertWorkflow("workflow_provider_failure_final_test", 1);
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        WorkerFailedRequest request = new WorkerFailedRequest(
                "PROVIDER_ERROR", "charged upstream", "PROVIDER_CALL", true,
                new BigDecimal("0.25"), "CNY", "UPSTREAM_500", "provider-request-1",
                10, 5, 1, "worker-claim"
        );

        assertThat(callbacks.failed(active.getChildTaskId(), request)).isTrue();
        assertThat(callbacks.failed(active.getChildTaskId(), request)).isFalse();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits, provider_cost, billing_usage_id "
                        + "FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("charged_credits", 0)
                .containsEntry("provider_cost", new BigDecimal("0.250000"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);
    }

    @Test
    void successCallbackPersistsReportedProviderAccountingExactlyOnce() throws Exception {
        stepId = insertWorkflow("workflow_provider_success_callback_api_test", 1);
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        String body = """
                {
                  "resourceType": "JSON",
                  "contentText": "{\\\"ok\\\":true}",
                  "promptTokens": 10,
                  "completionTokens": 5,
                  "billableUnits": 1,
                  "providerCostAmount": 0.345678,
                  "providerCostCurrency": "usd",
                  "providerRequestId": "success-provider-request-1",
                  "providerCalled": true,
                  "claimToken": "worker-claim"
                }
                """;
        String callbackPath = "/api/internal/v1/tasks/%d/success".formatted(active.getChildTaskId());

        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider_request_id FROM workflow_step_attempts WHERE id = ?",
                active.getId()
        )).containsEntry("provider_request_id", "success-provider-request-1");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider_cost, provider_cost_currency FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("provider_cost", new BigDecimal("0.345678"))
                .containsEntry("provider_cost_currency", "USD");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT vendor_cost_amount, provider_cost_currency, provider_request_id "
                        + "FROM billing_usage_logs WHERE idempotency_key = ?",
                active.getClaimToken() + ":usage"
        )).containsEntry("vendor_cost_amount", new BigDecimal("0.345678"))
                .containsEntry("provider_cost_currency", "USD")
                .containsEntry("provider_request_id", "success-provider-request-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
    }

    @Test
    void successWithoutReportedProviderCostDoesNotBookPricingEstimateAsActualCost() throws Exception {
        stepId = insertWorkflow("workflow_unknown_provider_cost_callback_api_test", 1);
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        String body = """
                {
                  "resourceType": "JSON",
                  "contentText": "{\\"ok\\":true}",
                  "promptTokens": 10,
                  "completionTokens": 5,
                  "billableUnits": 1,
                  "providerRequestId": "unknown-cost-provider-request-1",
                  "providerCalled": true,
                  "claimToken": "worker-claim"
                }
                """;
        String callbackPath = "/api/internal/v1/tasks/%d/success".formatted(active.getChildTaskId());

        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider_cost, provider_cost_currency FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("provider_cost", null)
                .containsEntry("provider_cost_currency", "UNKNOWN");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT vendor_cost_amount, provider_cost_currency, provider_charged, provider_request_id "
                        + "FROM billing_usage_logs WHERE idempotency_key = ?",
                active.getClaimToken() + ":usage"
        )).containsEntry("vendor_cost_amount", new BigDecimal("0.000000"))
                .containsEntry("provider_cost_currency", "UNKNOWN")
                .containsEntry("provider_charged", 0)
                .containsEntry("provider_request_id", "unknown-cost-provider-request-1");
        assertThat(chargeReconciler.reconcileRun(runIdForStep()))
                .extracting("invalidCreditTransitions", "consistent")
                .containsExactly(0, true);
    }

    @Test
    void duplicateSuccessCanAttachActualProviderCostReportedLaterExactlyOnce() throws Exception {
        stepId = insertWorkflow("workflow_late_success_provider_cost_callback_api_test", 1);
        configureSnapshotPricedStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        String callbackPath = "/api/internal/v1/tasks/%d/success".formatted(active.getChildTaskId());
        String unknownCostBody = """
                {
                  "resourceType": "JSON",
                  "contentText": "{\\"ok\\":true}",
                  "promptTokens": 10,
                  "completionTokens": 5,
                  "billableUnits": 1,
                  "providerRequestId": "late-success-provider-request-1",
                  "providerCalled": true,
                  "claimToken": "worker-claim"
                }
                """;
        String actualCostBody = """
                {
                  "resourceType": "JSON",
                  "contentText": "{\\"ok\\":true}",
                  "promptTokens": 10,
                  "completionTokens": 5,
                  "billableUnits": 1,
                  "providerCostAmount": 0.345678,
                  "providerCostCurrency": "usd",
                  "providerRequestId": "late-success-provider-request-1",
                  "providerCalled": true,
                  "claimToken": "worker-claim"
                }
                """;

        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, unknownCostBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownCostBody))
                .andExpect(status().isOk());
        Map<String, Object> creditsAfterFirstCallback = jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        );

        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, actualCostBody)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(actualCostBody))
                    .andExpect(status().isOk());
        }
        String conflictingCostBody = actualCostBody.replace("0.345678", "0.445678");
        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, conflictingCostBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingCostBody))
                .andExpect(status().is5xxServerError());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider_cost, provider_cost_currency FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("provider_cost", new BigDecimal("0.345678"))
                .containsEntry("provider_cost_currency", "USD");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT vendor_cost_amount, provider_cost_currency, provider_charged, provider_request_id "
                        + "FROM billing_usage_logs WHERE idempotency_key = ?",
                active.getClaimToken() + ":usage"
        )).containsEntry("vendor_cost_amount", new BigDecimal("0.345678"))
                .containsEntry("provider_cost_currency", "USD")
                .containsEntry("provider_charged", 1)
                .containsEntry("provider_request_id", "late-success-provider-request-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).isEqualTo(creditsAfterFirstCallback);
    }

    @Test
    void failedCallbackPersistsReportedProviderAccountingExactlyOnce() throws Exception {
        stepId = insertWorkflow("workflow_provider_failure_callback_api_test", 1);
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        String body = """
                {
                  "errorCode": "PROVIDER_ERROR",
                  "errorMessage": "provider charged before failure",
                  "failureStage": "PROVIDER_POLLING",
                  "providerCharged": true,
                  "providerCostAmount": 0.456789,
                  "providerCostCurrency": "usd",
                  "providerErrorCode": "UPSTREAM_500",
                  "providerRequestId": "failed-provider-request-1",
                  "claimToken": "worker-claim"
                }
                """;
        String callbackPath = "/api/internal/v1/tasks/%d/failed".formatted(active.getChildTaskId());

        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider_request_id FROM workflow_step_attempts WHERE id = ?",
                active.getId()
        )).containsEntry("provider_request_id", "failed-provider-request-1");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, provider_cost, provider_cost_currency FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("provider_cost", new BigDecimal("0.456789"))
                .containsEntry("provider_cost_currency", "USD");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT vendor_cost_amount, provider_cost_currency, provider_request_id, provider_error_code "
                        + "FROM billing_usage_logs WHERE idempotency_key = ?",
                active.getClaimToken() + ":usage"
        )).containsEntry("vendor_cost_amount", new BigDecimal("0.456789"))
                .containsEntry("provider_cost_currency", "USD")
                .containsEntry("provider_request_id", "failed-provider-request-1")
                .containsEntry("provider_error_code", "UPSTREAM_500");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = 1"
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);
    }

    @Test
    void failedCallbackProviderNotChargedOverridesContradictoryReportedAmount() throws Exception {
        stepId = insertWorkflow("workflow_provider_not_charged_callback_api_test", 1);
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        claim(active);
        String body = """
                {
                  "errorCode": "PROVIDER_ERROR",
                  "errorMessage": "provider confirmed no charge",
                  "failureStage": "PROVIDER_CALLBACK",
                  "providerCharged": false,
                  "providerCostAmount": 9.990000,
                  "providerCostCurrency": "CNY",
                  "providerRequestId": "provider-not-charged-request-1",
                  "claimToken": "worker-claim"
                }
                """;
        String callbackPath = "/api/internal/v1/tasks/%d/failed".formatted(active.getChildTaskId());

        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, provider_cost, billing_usage_id FROM workflow_step_charges WHERE attempt_id = ?",
                active.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("provider_cost", null)
                .containsEntry("billing_usage_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                active.getClaimToken() + ":usage"
        )).isZero();
    }

    @Test
    void terminalChildCallbackCanAttachProviderAccountingReportedLater() throws Exception {
        stepId = insertWorkflow("workflow_late_provider_accounting_callback_api_test", 2);
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        claim(first);
        String firstBody = """
                {
                  "errorCode": "MODEL_TIMEOUT",
                  "errorMessage": "provider result was initially unknown",
                  "failureStage": "PROVIDER_POLLING",
                  "providerCharged": false,
                  "claimToken": "worker-claim"
                }
                """;
        String callbackPath = "/api/internal/v1/tasks/%d/failed".formatted(first.getChildTaskId());
        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, firstBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isOk());

        String lateBody = """
                {
                  "errorCode": "PROVIDER_ERROR",
                  "errorMessage": "provider billing arrived later",
                  "failureStage": "PROVIDER_CALLBACK",
                  "providerCharged": true,
                  "providerCostAmount": 0.567890,
                  "providerCostCurrency": "usd",
                  "providerRequestId": "late-provider-request-1",
                  "claimToken": "worker-claim"
                }
                """;
        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, lateBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lateBody))
                .andExpect(status().isOk());
        mockMvc.perform(signed(post(callbackPath), "POST", callbackPath, lateBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lateBody))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider_cost, provider_cost_currency FROM workflow_step_charges WHERE attempt_id = ?",
                first.getId()
        )).containsEntry("provider_cost", new BigDecimal("0.567890"))
                .containsEntry("provider_cost_currency", "USD");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                first.getClaimToken() + ":usage"
        )).isEqualTo(1);
    }

    @Test
    void lateFailureRecordsProviderCostMetricOnlyOnce() {
        configurePaidStep(20, 18, 100);
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        claim(first);
        callbacks.failed(first.getChildTaskId(), failure("MODEL_TIMEOUT", "timeout"));
        WorkerFailedRequest lateFailure = new WorkerFailedRequest(
                "PROVIDER_ERROR", "charged upstream", "PROVIDER_CALL", true,
                new BigDecimal("0.25"), "CNY", "UPSTREAM_500", "late-provider-request",
                10, 5, 1, "worker-claim"
        );
        double recordedBefore = lateCallbackMetric("recorded_provider_cost");

        assertThat(callbacks.failed(first.getChildTaskId(), lateFailure)).isFalse();
        assertThat(callbacks.failed(first.getChildTaskId(), lateFailure)).isFalse();

        assertThat(lateCallbackMetric("recorded_provider_cost") - recordedBefore).isEqualTo(1);
    }

    private void claim(WorkflowStepAttempt attempt) {
        assertThat(internalTaskService.claim(
                attempt.getChildTaskId(),
                new ClaimTaskRequest("workflow-worker", "worker-claim")
        ).claimed()).isTrue();
    }

    private double billingMetric(String state) {
        return meterRegistry.counter("workflow_billing_state_total", "state", state).count();
    }

    private double lateCallbackMetric(String result) {
        return meterRegistry.counter("workflow_late_callback_total", "result", result).count();
    }

    private WorkerSuccessRequest success(String output) {
        return new WorkerSuccessRequest("JSON", output, 10, 5, 1, "worker-claim");
    }

    private WorkerSuccessRequest successWithProviderCost(String output, String providerCost) {
        return new WorkerSuccessRequest(
                "JSON", output, 10, 5, 1,
                new BigDecimal(providerCost), "CNY", null, true, "worker-claim"
        );
    }

    private WorkerFailedRequest failure(String code, String message) {
        return new WorkerFailedRequest(
                code,
                message,
                "PROVIDER_CALL",
                false,
                null,
                null,
                null,
                0,
                0,
                0,
                "worker-claim"
        );
    }

    private long insertWorkflow(String toolCode, int maxAttempts) {
        jdbcTemplate.update("DELETE FROM workflow_step_charges");
        jdbcTemplate.update("DELETE FROM workflow_step_attempts");
        jdbcTemplate.update("DELETE FROM task_outbox_events");
        jdbcTemplate.update("DELETE FROM workflow_run_steps");
        jdbcTemplate.update("DELETE FROM workflow_runs");
        jdbcTemplate.update(
                "DELETE FROM ai_tasks WHERE idempotency_key LIKE 'workflow:%' OR idempotency_key = ?",
                "root-" + toolCode
        );
        jdbcTemplate.update("DELETE FROM tool_workflow_versions WHERE workflow_id IN (SELECT id FROM tool_workflows WHERE workflow_name = ?)", toolCode);
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE workflow_name = ?", toolCode);
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code = ?", toolCode);

        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, 'Workflow Callback Test', 1, 'test', 'ONLINE', 1,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, 1, 0)
                """, toolCode);
        long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, toolCode);
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker"}}]
                """;
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, ?, ?, '[]', '{}', 1, 'PUBLISHED', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, toolCode, nodes);
        long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE workflow_name = ?", Long.class, toolCode);
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, 1, ?, '[]', '{}', '{}', '1', 'p0', ?, '{}', '{}',
                          '{"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{"pricingSource":"TOOL_FALLBACK","maxCreditCost":1,"maxProviderCostCny":0.10,"fallbackChargeCredits":1,"staticParams":{},"modelPricingSnapshot":null,"pricingPolicy":{"markupRatio":1.5,"minCredits":0,"imageEstimateInputTokens":8000,"imageEstimateOutputTokens":8000,"rules":[]}}}}',
                          '{}', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, nodes, "hash-" + toolCode);
        long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ?", Long.class, workflowId);
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, progress_message,
                  params_json, idempotency_key, estimated_credit_cost, queued_at, started_at
                ) VALUES (?, 1, ?, 'PROCESSING', 5, 'running', '{}', ?, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "ROOT-" + toolCode, toolId, "root-" + toolCode);
        long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE idempotency_key = ?", Long.class, "root-" + toolCode);
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, workflow_version_id,
                  root_task_id, launch_source, client_request_id, status, revision,
                  cancellation_generation, input_json, context_json, billing_status,
                  started_at, created_at, updated_at
                ) VALUES (1, ?, ?, 1, ?, ?, 'AGENTS_PAGE', ?, 'RUNNING', 0, 0,
                          '{"prompt":"hello"}', '{}', 'CLEAR', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, workflowId, versionId, rootTaskId, "request-" + toolCode);
        long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE root_task_id = ?", Long.class, rootTaskId);
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision,
                  attempt, attempt_count, max_attempts, input_json
                ) VALUES (?, 'worker', 1, 'LLM_TEXT', 'READY', 0, 0, 0, ?,
                          '{"form":{"prompt":"hello"}}')
                """, runId, maxAttempts);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'worker'",
                Long.class,
                runId
        );
    }

    private void configurePaidStep(int maxCreditCost, int actualCreditCost, int balance) {
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = 1");
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = 1");
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (1, ?, ?, 0, 0, ?, 0, 'ACTIVE')
                """, balance, balance, balance);
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                  "parameters":{"maxCreditCost":%d}}}]
                """.formatted(maxCreditCost);
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET nodes_json = ?
                WHERE id = (
                  SELECT r.workflow_version_id
                  FROM workflow_runs r
                  JOIN workflow_run_steps s ON s.run_id = r.id
                  WHERE s.id = ?
                )
                """, nodes, stepId);
        jdbcTemplate.update("""
                UPDATE ai_tools
                SET estimated_credit_cost = ?
                WHERE id = (
                  SELECT r.tool_id
                  FROM workflow_runs r
                  JOIN workflow_run_steps s ON s.run_id = r.id
                  WHERE s.id = ?
                )
                """, actualCreditCost, stepId);
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET billing_policy_json = ?
                WHERE id = (
                  SELECT r.workflow_version_id
                  FROM workflow_runs r
                  JOIN workflow_run_steps s ON s.run_id = r.id
                  WHERE s.id = ?
                )
                """, "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"worker\":{"
                + "\"pricingSource\":\"TOOL_FALLBACK\""
                + ",\"maxCreditCost\":" + maxCreditCost
                + ",\"maxProviderCostCny\":0.10"
                + ",\"fallbackChargeCredits\":" + actualCreditCost
                + ",\"staticParams\":{}"
                + ",\"modelPricingSnapshot\":null"
                + ",\"pricingPolicy\":{\"markupRatio\":1.5,\"minCredits\":0,"
                + "\"imageEstimateInputTokens\":8000,\"imageEstimateOutputTokens\":8000,\"rules\":[]}}}}", stepId);
    }

    private void configureSnapshotPricedStep(int maxCreditCost, int balance) {
        configurePaidStep(maxCreditCost, 1, balance);
        jdbcTemplate.update("UPDATE workflow_run_steps SET input_json = '{\"quality\":\"hd\"}' WHERE id = ?", stepId);
        String policy = """
                {"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{
                  "pricingSource":"MODEL_PRICING",
                  "maxCreditCost":%d,
                  "maxProviderCostCny":0.10,
                  "fallbackChargeCredits":0,
                  "staticParams":{"quality":"sd"},
                  "modelPricingSnapshot":{
                    "id":99001,
                    "provider":"snapshot-provider",
                    "modelName":"snapshot-model",
                    "inputTokenPricePer1k":0,
                    "outputTokenPricePer1k":0,
                    "inputTokenPricePer1m":0,
                    "outputTokenPricePer1m":0,
                    "billingUnit":"PER_CALL",
                    "unitPrice":0.10
                  },
                  "pricingPolicy":{
                    "markupRatio":1.5,
                    "minCredits":0,
                    "imageEstimateInputTokens":8000,
                    "imageEstimateOutputTokens":8000,
                    "rules":[{
                      "paramKey":"quality",
                      "ruleType":"MULTIPLIER",
                      "matchOp":"EQ",
                      "matchValue":"hd",
                      "factor":2.0,
                      "extraCredits":0,
                      "priority":1
                    }]
                  }
                }}}
                """.formatted(maxCreditCost);
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET billing_policy_json = ?
                WHERE id = (
                  SELECT r.workflow_version_id
                  FROM workflow_runs r
                  JOIN workflow_run_steps s ON s.run_id = r.id
                  WHERE s.id = ?
                )
                """, policy, stepId);
    }

    private void attachPerCallModelSnapshot(Long childTaskId, String unitPrice) {
        String snapshot = """
                {
                  "id":99001,
                  "displayName":"Published snapshot model",
                  "configCode":"published-snapshot-model",
                  "provider":"snapshot-provider",
                  "modelName":"snapshot-model",
                  "billingUnit":"PER_CALL",
                  "unitPrice":%s,
                  "capabilities":[],
                  "providerMetadataVersion":"test"
                }
                """.formatted(unitPrice);
        jdbcTemplate.update("UPDATE ai_tasks SET model_snapshot_json = ? WHERE id = ?", snapshot, childTaskId);
    }

    private ObjectNode billingNodePolicy() throws Exception {
        String policyJson = jdbcTemplate.queryForObject("""
                SELECT version.billing_policy_json
                FROM tool_workflow_versions version
                JOIN workflow_runs run_log ON run_log.workflow_version_id = version.id
                JOIN workflow_run_steps step_log ON step_log.run_id = run_log.id
                WHERE step_log.id = ?
                """, String.class, stepId);
        return ((ObjectNode) objectMapper.readTree(policyJson).path("nodePolicies").path("worker")).deepCopy();
    }

    private void updateBillingNodePolicy(ObjectNode nodePolicy) throws Exception {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("mode", "WORKFLOW_STEP");
        policy.putObject("nodePolicies").set("worker", nodePolicy);
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET billing_policy_json = ?
                WHERE id = (
                  SELECT run_log.workflow_version_id
                  FROM workflow_runs run_log
                  JOIN workflow_run_steps step_log ON step_log.run_id = run_log.id
                  WHERE step_log.id = ?
                )
                """, objectMapper.writeValueAsString(policy), stepId);
    }

    private long toolIdForStep() {
        return jdbcTemplate.queryForObject("""
                SELECT r.tool_id
                FROM workflow_runs r
                JOIN workflow_run_steps s ON s.run_id = r.id
                WHERE s.id = ?
                """, Long.class, stepId);
    }

    private long runIdForStep() {
        return jdbcTemplate.queryForObject(
                "SELECT run_id FROM workflow_run_steps WHERE id = ?",
                Long.class,
                stepId
        );
    }
}
