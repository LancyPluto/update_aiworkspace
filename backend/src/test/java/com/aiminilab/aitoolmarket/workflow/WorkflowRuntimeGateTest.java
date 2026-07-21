package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimeGateTest {

    @Test
    void defaultConfigurationBlocksEveryNewRun() {
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(new WorkflowRuntimeProperties());

        assertThat(gate.evaluateNewRun(11L, false, 0, 0))
                .isEqualTo(WorkflowRuntimeGate.Decision.denied("runtime_disabled"));
    }

    @Test
    void startupRemainsBlockedUntilAFullCleanReconciliationCycle() {
        WorkflowRuntimeProperties properties = enabledProperties();
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(properties);

        assertThat(gate.evaluateNewRun(11L, false, 0, 0).reason())
                .isEqualTo("reconciliation_not_healthy");

        gate.markReconciliationHealthyAfterFullScan(gate.reconciliationFailureGeneration());

        assertThat(gate.evaluateNewRun(11L, false, 0, 0).allowed()).isTrue();
    }

    @Test
    void explicitUsersAndDeterministicCanaryAreSupported() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setAllowedUserIds(List.of(11L));
        properties.setCanaryPercentage(10);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, false, 0, 0).allowed()).isTrue();
        assertThat(gate.evaluateNewRun(105L, false, 0, 0).allowed()).isTrue();
        assertThat(gate.evaluateNewRun(115L, false, 0, 0).reason())
                .isEqualTo("user_not_in_canary");
    }

    @Test
    void globalExecutionSwitchIsEnforced() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setExecutionEnabled(false);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, false, 0, 0).reason())
                .isEqualTo("execution_disabled");
    }

    @Test
    void shadowBillingNeverAllowsAProviderChargingRun() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setShadowBillingEnabled(true);
        properties.setMaxRunCostCredits(100);
        properties.setMaxUserDailyCostCredits(1000);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, 10, 0).reason())
                .isEqualTo("paid_run_requires_real_billing");
    }

    @Test
    void conflictingRealAndShadowBillingSwitchesFailClosed() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setRealBillingEnabled(true);
        properties.setShadowBillingEnabled(true);
        properties.setMaxRunCostCredits(100);
        properties.setMaxUserDailyCostCredits(1000);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, 10, 0).reason())
                .isEqualTo("billing_mode_conflict");
    }

    @Test
    void invalidCanaryPercentageDoesNotBecomeFullRollout() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setAllowedUserIds(List.of());
        properties.setCanaryPercentage(101);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(42L, false, 0, 0).reason())
                .isEqualTo("user_not_in_canary");
    }

    @Test
    void paidRunsRequireRealBillingAndCreditBudgets() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setRealBillingEnabled(true);
        properties.setMaxRunCostCredits(100);
        properties.setMaxUserDailyCostCredits(150);
        WorkflowRuntimeGate gate = healthyGate(properties);

        assertThat(gate.evaluateNewRun(11L, true, 101, 0).reason())
                .isEqualTo("run_cost_limit_exceeded");
        assertThat(gate.evaluateNewRun(11L, true, 60, 100).reason())
                .isEqualTo("user_daily_cost_limit_exceeded");
        assertThat(gate.evaluateNewRun(11L, true, 50, 100).allowed()).isTrue();
    }

    @Test
    void reconciliationMismatchImmediatelyBlocksNewRuns() {
        WorkflowRuntimeGate gate = healthyGate(enabledProperties());
        assertThat(gate.evaluateNewRun(11L, false, 0, 0).allowed()).isTrue();

        gate.markReconciliationUnhealthy();

        assertThat(gate.evaluateNewRun(11L, false, 0, 0).reason())
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
