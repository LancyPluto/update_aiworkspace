package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimeGateTest {

    @Test
    void defaultConfigurationBlocksEveryNewRun() {
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(new WorkflowRuntimeProperties());

        assertThat(gate.evaluateNewRun(false, 0))
                .isEqualTo(WorkflowRuntimeGate.Decision.denied("runtime_disabled"));
    }

    @Test
    void globalExecutionSwitchIsEnforced() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setExecutionEnabled(false);

        assertThat(new WorkflowRuntimeGate(properties).evaluateNewRun(false, 0).reason())
                .isEqualTo("execution_disabled");
    }

    @Test
    void reconciliationHealthIsTrackedWithoutBlockingNewRuns() {
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(enabledProperties());

        assertThat(gate.isReconciliationHealthy()).isFalse();
        assertThat(gate.evaluateNewRun(false, 0).allowed()).isTrue();

        gate.markReconciliationUnhealthy();

        assertThat(gate.isReconciliationHealthy()).isFalse();
        assertThat(gate.evaluateNewRun(false, 0).allowed()).isTrue();
    }

    @Test
    void paidRunsRequireRealBilling() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setShadowBillingEnabled(true);

        assertThat(new WorkflowRuntimeGate(properties).evaluateNewRun(true, 10).reason())
                .isEqualTo("paid_run_requires_real_billing");
    }

    @Test
    void conflictingRealAndShadowBillingSwitchesFailClosed() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setRealBillingEnabled(true);
        properties.setShadowBillingEnabled(true);

        assertThat(new WorkflowRuntimeGate(properties).evaluateNewRun(true, 10).reason())
                .isEqualTo("billing_mode_conflict");
    }

    @Test
    void paidRunsRequireAValidEstimateButHaveNoGateLevelBudgetCap() {
        WorkflowRuntimeProperties properties = enabledProperties();
        properties.setRealBillingEnabled(true);
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(properties);

        assertThat(gate.evaluateNewRun(true, 0).reason()).isEqualTo("paid_run_cost_unknown");
        assertThat(gate.evaluateNewRun(true, Long.MAX_VALUE).allowed()).isTrue();
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
        return properties;
    }
}
