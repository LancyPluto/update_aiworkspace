package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimeGateTest {

    @Test
    void defaultConfigurationBlocksEveryNewRun() {
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(new WorkflowRuntimeProperties());

        assertThat(gate.evaluateNewRun(11L, true, false, 0, 0, BigDecimal.ZERO))
                .isEqualTo(WorkflowRuntimeGate.Decision.denied("runtime_disabled"));
    }

    @Test
    void startupRemainsBlockedUntilAFullCleanReconciliationCycle() {
        WorkflowRuntimeProperties properties = enabledProperties();
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, false, 0, 0, BigDecimal.ZERO).reason())
                .isEqualTo("reconciliation_not_healthy");

        gate.markReconciliationHealthyAfterFullScan(gate.reconciliationFailureGeneration());

        assertThat(gate.evaluateNewRun(11L, true, false, 0, 0, BigDecimal.ZERO).allowed()).isTrue();
    }

    @Test
    void explicitUsersAndDeterministicCanaryAreSupported() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setAllowedUserIds(List.of(11L));
        properties.setCanaryPercentage(10);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, false, 0, 0, BigDecimal.ZERO).allowed()).isTrue();
        assertThat(gate.evaluateNewRun(105L, true, false, 0, 0, BigDecimal.ZERO).allowed()).isTrue();
        assertThat(gate.evaluateNewRun(115L, true, false, 0, 0, BigDecimal.ZERO).reason())
                .isEqualTo("user_not_in_canary");
    }

    @Test
    void toolLevelExecutionSwitchIsEnforced() {
        WorkflowRuntimeGate gate = healthyGate(enabledProperties());

        assertThat(gate.evaluateNewRun(11L, false, false, 0, 0, BigDecimal.ZERO).reason())
                .isEqualTo("tool_execution_disabled");
    }

    @Test
    void shadowBillingNeverAllowsAProviderChargingRun() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setShadowBillingEnabled(true);
        properties.setMaxRunCostCredits(100);
        properties.setMaxUserDailyCostCredits(1000);
        properties.setMaxProviderDailyCostCny(new BigDecimal("100"));
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, true, 10, 0, BigDecimal.ZERO).reason())
                .isEqualTo("paid_run_requires_real_billing");
    }

    @Test
    void conflictingRealAndShadowBillingSwitchesFailClosed() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setRealBillingEnabled(true);
        properties.setShadowBillingEnabled(true);
        properties.setMaxRunCostCredits(100);
        properties.setMaxUserDailyCostCredits(1000);
        properties.setMaxProviderDailyCostCny(new BigDecimal("100"));
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, true, 10, 0, BigDecimal.ZERO).reason())
                .isEqualTo("billing_mode_conflict");
    }

    @Test
    void invalidCanaryPercentageDoesNotBecomeFullRollout() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setAllowedUserIds(List.of());
        properties.setCanaryPercentage(101);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(42L, true, false, 0, 0, BigDecimal.ZERO).reason())
                .isEqualTo("user_not_in_canary");
    }

    @Test
    void paidRunsRequireRealBillingAndBothCostBudgets() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setRealBillingEnabled(true);
        properties.setMaxRunCostCredits(100);
        properties.setMaxUserDailyCostCredits(150);
        properties.setMaxProviderDailyCostCny(new BigDecimal("10.00"));
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, true, 101, 0, BigDecimal.ZERO).reason())
                .isEqualTo("run_cost_limit_exceeded");
        assertThat(gate.evaluateNewRun(11L, true, true, 60, 100, BigDecimal.ZERO).reason())
                .isEqualTo("user_daily_cost_limit_exceeded");
        assertThat(gate.evaluateNewRun(11L, true, true, 50, 100, new BigDecimal("9.99")).allowed()).isTrue();
        assertThat(gate.evaluateNewRun(11L, true, true, 50, 100, new BigDecimal("10.00")).reason())
                .isEqualTo("provider_daily_cost_limit_exceeded");
    }

    @Test
    void reconciliationMismatchImmediatelyBlocksNewRuns() {
        WorkflowRuntimeGate gate = healthyGate(enabledProperties());
        assertThat(gate.evaluateNewRun(11L, true, false, 0, 0, BigDecimal.ZERO).allowed()).isTrue();

        gate.markReconciliationUnhealthy();

        assertThat(gate.evaluateNewRun(11L, true, false, 0, 0, BigDecimal.ZERO).reason())
                .isEqualTo("reconciliation_not_healthy");
    }

    @Test
    void staleCleanScanCannotOverwriteANewerMismatch() {
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(enabledProperties());
        long scanGeneration = gate.reconciliationFailureGeneration();

        gate.markReconciliationUnhealthy();

        assertThat(gate.markReconciliationHealthyAfterFullScan(scanGeneration)).isFalse();
        assertThat(gate.isReconciliationHealthy()).isFalse();
    }

    private WorkflowRuntimeProperties enabledProperties() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setEnabled(true);
        properties.setExecutionEnabled(true);
        properties.setAllowedUserIds(List.of(11L));
        return properties;
    }

    private WorkflowRuntimeGate healthyGate(WorkflowRuntimeProperties properties) {
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(properties);
        gate.markReconciliationHealthyAfterFullScan(gate.reconciliationFailureGeneration());
        return gate;
    }
}
