package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.service.WorkflowChargeReconciler;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_charge_usage_binding_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkflowChargeUsageBindingTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkflowChargeReconciler reconciler;

    @Autowired
    private WorkflowStepChargeMapper chargeMapper;

    @BeforeEach
    void cleanWorkflowLedger() {
        jdbcTemplate.update("DELETE FROM workflow_step_charges");
        jdbcTemplate.update("DELETE FROM workflow_step_attempts");
        jdbcTemplate.update("DELETE FROM workflow_run_steps");
        jdbcTemplate.update("DELETE FROM workflow_runs");
        jdbcTemplate.update("DELETE FROM billing_usage_logs");
        jdbcTemplate.update("DELETE FROM credit_logs");
    }

    @Test
    void equalAggregateTotalsCannotHideMissingAndMisboundUsageRows() {
        Long runId = insertRun();
        Long firstStepId = insertCapturedStep(runId, "first", 10, "workflow:first");
        Long secondStepId = insertCapturedStep(runId, "second", 20, "workflow:second");

        Long wrongUsageId = insertUsage(
                "wrong-usage-key",
                secondStepId + 1000,
                999L,
                30,
                new BigDecimal("0.30"),
                "wrong-provider-request"
        );
        jdbcTemplate.update(
                "UPDATE workflow_step_charges SET billing_usage_id = ? WHERE step_id = ?",
                wrongUsageId,
                secondStepId
        );

        var result = reconciler.reconcileRun(runId);

        assertThat(result.capturedCredits()).isEqualTo(30);
        assertThat(result.usageCredits()).isEqualTo(30);
        assertThat(result.creditLogDeductions()).isEqualTo(30);
        assertThat(result.consistent()).isFalse();
        assertThat(firstStepId).isNotEqualTo(secondStepId);
    }

    @Test
    void correctlyBoundCapturedUsageRemainsConsistent() {
        Long runId = insertRun();
        String key = "workflow:valid";
        Long stepId = insertCapturedStep(runId, "valid", 10, key);
        Long usageId = insertValidCapturedUsage(stepId, key, 10, new BigDecimal("0.10"));
        jdbcTemplate.update(
                "UPDATE workflow_step_charges SET billing_usage_id = ? WHERE step_id = ?",
                usageId,
                stepId
        );

        var result = reconciler.reconcileRun(runId);

        assertThat(result.invalidCreditTransitions()).isZero();
        assertThat(result.consistent()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "idempotencyKey", "user", "sourceType", "sourceId", "chargedCredits",
            "customerChargeCredits", "costAmount", "vendorCost", "providerRequest",
            "providerCharged", "outcome", "currency"
    })
    void eachUsageBindingFieldIsRequired(String mismatch) {
        Long runId = insertRun();
        String key = "workflow:field:" + mismatch;
        Long stepId = insertCapturedStep(runId, "field-" + mismatch, 10, key);
        Long usageId = insertValidCapturedUsage(stepId, key, 10, new BigDecimal("0.10"));
        jdbcTemplate.update(
                "UPDATE workflow_step_charges SET billing_usage_id = ? WHERE step_id = ?",
                usageId,
                stepId
        );
        corruptUsageBinding(mismatch, usageId, stepId);

        assertThat(chargeMapper.countInvalidUsageBindings(runId)).isOne();
        assertThat(reconciler.reconcileRun(runId).consistent()).isFalse();
    }

    @Test
    void correctlyBoundReleasedProviderCostRemainsConsistent() {
        Long runId = insertRun();
        jdbcTemplate.update("UPDATE workflow_runs SET status = 'FAILED' WHERE id = ?", runId);
        String key = "workflow:released";
        Long stepId = insertReleasedStep(runId, "released", 20, key, new BigDecimal("0.25"));
        Long usageId = insertValidReleasedUsage(stepId, key, new BigDecimal("0.25"));
        jdbcTemplate.update(
                "UPDATE workflow_step_charges SET billing_usage_id = ? WHERE step_id = ?",
                usageId,
                stepId
        );

        var result = reconciler.reconcileRun(runId);

        assertThat(result.invalidCreditTransitions()).isZero();
        assertThat(result.consistent()).isTrue();
    }

    private Long insertRun() {
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                    user_id, tool_id, workflow_id, workflow_version, root_task_id,
                    launch_source, client_request_id, status
                ) VALUES (7, 101, 201, 1, 301, 'AGENTS_PAGE', 'usage-binding-test', 'SUCCESS')
                """);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE client_request_id = 'usage-binding-test'",
                Long.class
        );
    }

    private Long insertCapturedStep(Long runId, String nodeId, int chargedCredits, String key) {
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(run_id, node_id, node_def_type, status)
                VALUES (?, ?, 'LLM_TEXT', 'SUCCESS')
                """, runId, nodeId);
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = ?",
                Long.class,
                runId,
                nodeId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_attempts(
                    step_id, attempt_no, status, claim_token, provider_request_id
                ) VALUES (?, 1, 'SUCCESS', ?, ?)
                """, stepId, key, key + ":provider-request");
        Long attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_step_attempts WHERE claim_token = ?",
                Long.class,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_charges(
                    run_id, step_id, attempt_id, user_id, status, reserved_credits,
                    charged_credits, provider_cost, provider_cost_currency, idempotency_key
                ) VALUES (?, ?, ?, 7, 'CAPTURED', ?, ?, ?, 'CNY', ?)
                """, runId, stepId, attemptId, chargedCredits, chargedCredits,
                BigDecimal.valueOf(chargedCredits, 2), key);
        insertCreditLog(stepId, key, chargedCredits, "FREEZE", 0, chargedCredits);
        insertCreditLog(stepId, key, chargedCredits, "DEDUCT", chargedCredits, -chargedCredits);
        return stepId;
    }

    private Long insertReleasedStep(Long runId,
                                    String nodeId,
                                    int reservedCredits,
                                    String key,
                                    BigDecimal providerCost) {
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(run_id, node_id, node_def_type, status)
                VALUES (?, ?, 'LLM_TEXT', 'FAILED')
                """, runId, nodeId);
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = ?",
                Long.class,
                runId,
                nodeId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_attempts(
                    step_id, attempt_no, status, claim_token, provider_request_id
                ) VALUES (?, 1, 'FAILED', ?, ?)
                """, stepId, key, key + ":provider-request");
        Long attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_step_attempts WHERE claim_token = ?",
                Long.class,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_charges(
                    run_id, step_id, attempt_id, user_id, status, reserved_credits,
                    charged_credits, provider_cost, provider_cost_currency, idempotency_key
                ) VALUES (?, ?, ?, 7, 'RELEASED', ?, 0, ?, 'CNY', ?)
                """, runId, stepId, attemptId, reservedCredits, providerCost, key);
        insertCreditLog(stepId, key, reservedCredits, "FREEZE", 0, reservedCredits);
        insertCreditLog(stepId, key, reservedCredits, "RELEASE", 0, -reservedCredits);
        return stepId;
    }

    private void insertCreditLog(Long stepId,
                                 String key,
                                 int credits,
                                 String logType,
                                 int amount,
                                 int frozenAmount) {
        boolean freeze = "FREEZE".equals(logType);
        boolean deduct = "DEDUCT".equals(logType);
        jdbcTemplate.update("""
                INSERT INTO credit_logs(
                    user_id, account_id, source_type, source_ref, log_type, amount,
                    frozen_amount, balance_before, balance_after, frozen_before,
                    frozen_after, idempotency_key
                ) VALUES (7, 1, 'WORKFLOW_STEP', ?, ?, ?, ?, 100, ?, ?, ?, ?)
                """,
                stepId,
                logType,
                amount,
                frozenAmount,
                deduct ? 100 - credits : 100,
                freeze ? 0 : credits,
                freeze ? credits : 0,
                key + (freeze ? ":reserve" : deduct ? ":capture" : ":release")
        );
    }

    private Long insertUsage(String idempotencyKey,
                             Long sourceId,
                             Long userId,
                             int chargedCredits,
                             BigDecimal providerCost,
                             String providerRequestId) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                    idempotency_key, source_type, source_id, user_id, provider,
                    model_name, cost_amount, vendor_cost_amount, charged_credits,
                    customer_charge_credits, outcome, provider_request_id, provider_charged
                ) VALUES (?, 'WRONG_SOURCE', ?, ?, 'wrong-provider', 'wrong-model',
                          ?, ?, ?, ?, 'SUCCESS', ?, 1)
                """,
                idempotencyKey,
                sourceId,
                userId,
                providerCost,
                providerCost,
                chargedCredits,
                chargedCredits,
                providerRequestId
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM billing_usage_logs WHERE idempotency_key = ?",
                Long.class,
                idempotencyKey
        );
    }

    private Long insertValidCapturedUsage(Long stepId,
                                          String chargeKey,
                                          int chargedCredits,
                                          BigDecimal providerCost) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                    idempotency_key, source_type, source_id, user_id, provider,
                    model_name, cost_amount, vendor_cost_amount, charged_credits,
                    customer_charge_credits, outcome, provider_request_id, provider_charged
                ) VALUES (?, 'WORKFLOW_STEP', ?, 7, 'test-provider', 'test-model',
                          ?, ?, ?, ?, 'SUCCESS', ?, 1)
                """,
                chargeKey + ":usage",
                stepId,
                providerCost,
                providerCost,
                chargedCredits,
                chargedCredits,
                chargeKey + ":provider-request"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM billing_usage_logs WHERE idempotency_key = ?",
                Long.class,
                chargeKey + ":usage"
        );
    }

    private Long insertValidReleasedUsage(Long stepId,
                                          String chargeKey,
                                          BigDecimal providerCost) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                    idempotency_key, source_type, source_id, user_id, provider,
                    model_name, cost_amount, vendor_cost_amount, charged_credits,
                    customer_charge_credits, outcome, provider_request_id, provider_charged
                ) VALUES (?, 'WORKFLOW_STEP', ?, 7, 'test-provider', 'test-model',
                          ?, ?, 0, 0, 'FAILED', ?, 1)
                """,
                chargeKey + ":usage",
                stepId,
                providerCost,
                providerCost,
                chargeKey + ":provider-request"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM billing_usage_logs WHERE idempotency_key = ?",
                Long.class,
                chargeKey + ":usage"
        );
    }

    private void corruptUsageBinding(String mismatch, Long usageId, Long stepId) {
        switch (mismatch) {
            case "idempotencyKey" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET idempotency_key = 'wrong-key' WHERE id = ?", usageId);
            case "user" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET user_id = 999 WHERE id = ?", usageId);
            case "sourceType" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET source_type = 'WRONG_SOURCE' WHERE id = ?", usageId);
            case "sourceId" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET source_id = ? WHERE id = ?", stepId + 1000, usageId);
            case "chargedCredits" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET charged_credits = 9 WHERE id = ?", usageId);
            case "customerChargeCredits" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET customer_charge_credits = 9 WHERE id = ?", usageId);
            case "costAmount" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET cost_amount = 0.99 WHERE id = ?", usageId);
            case "vendorCost" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET vendor_cost_amount = 0.99 WHERE id = ?", usageId);
            case "providerRequest" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET provider_request_id = 'wrong-request' WHERE id = ?", usageId);
            case "providerCharged" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET provider_charged = 0 WHERE id = ?", usageId);
            case "outcome" -> jdbcTemplate.update(
                    "UPDATE billing_usage_logs SET outcome = 'FAILED' WHERE id = ?", usageId);
            case "currency" -> jdbcTemplate.update(
                    "UPDATE workflow_step_charges SET provider_cost_currency = 'USD' WHERE step_id = ?", stepId);
            default -> throw new IllegalArgumentException("Unknown mismatch: " + mismatch);
        }
    }
}
