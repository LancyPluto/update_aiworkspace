package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowMetricsTest {

    @Test
    void recordsOnlyBoundedWorkflowDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowMetrics metrics = new WorkflowMetrics(registry);

        metrics.recordRecovery(WorkflowMetrics.RecoveryResult.RECOVERED);
        metrics.recordRecovery(WorkflowMetrics.RecoveryResult.CANCELLATION_PENDING_RECONCILIATION);
        metrics.recordReconciliation(true);
        metrics.recordReconciliation(false);
        metrics.recordCasConflict(WorkflowMetrics.CasOperation.RECOVERY);
        metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_GENERATION);
        metrics.recordBillingState(WorkflowMetrics.BillingState.RESERVED);
        metrics.recordCostAlert(WorkflowMetrics.CostAlertResult.DELIVERED);

        assertThat(registry.counter("workflow_recovery_total", "result", "recovered").count()).isEqualTo(1);
        assertThat(registry.counter(
                "workflow_recovery_total", "result", "cancellation_pending_reconciliation").count())
                .isEqualTo(1);
        assertThat(registry.counter("workflow_reconciliation_total", "result", "consistent").count()).isEqualTo(1);
        assertThat(registry.counter("workflow_reconciliation_total", "result", "inconsistent").count()).isEqualTo(1);
        assertThat(registry.counter("workflow_cas_conflict_total", "operation", "recovery").count()).isEqualTo(1);
        assertThat(registry.counter("workflow_late_callback_total", "result", "rejected_generation").count()).isEqualTo(1);
        assertThat(registry.counter("workflow_billing_state_total", "state", "reserved").count()).isEqualTo(1);
        assertThat(registry.counter("workflow_cost_alert_total", "result", "delivered").count()).isEqualTo(1);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey()).doesNotContain("run", "user", "attempt", "tool")));
    }
}
