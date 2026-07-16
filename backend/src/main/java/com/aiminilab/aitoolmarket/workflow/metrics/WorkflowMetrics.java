package com.aiminilab.aitoolmarket.workflow.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class WorkflowMetrics {

    private final MeterRegistry meterRegistry;

    public WorkflowMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRecovery(RecoveryResult result) {
        meterRegistry.counter("workflow_recovery_total", "result", tag(result)).increment();
    }

    public void recordReconciliation(boolean consistent) {
        meterRegistry.counter(
                "workflow_reconciliation_total",
                "result",
                consistent ? "consistent" : "inconsistent"
        ).increment();
    }

    public void recordCasConflict(CasOperation operation) {
        meterRegistry.counter("workflow_cas_conflict_total", "operation", tag(operation)).increment();
    }

    public void recordLateCallback(LateCallbackResult result) {
        meterRegistry.counter("workflow_late_callback_total", "result", tag(result)).increment();
    }

    public void recordBillingState(BillingState state) {
        meterRegistry.counter("workflow_billing_state_total", "state", tag(state)).increment();
    }

    public void recordCostAlert(CostAlertResult result) {
        meterRegistry.counter("workflow_cost_alert_total", "result", tag(result)).increment();
    }

    private String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    public enum RecoveryResult {
        RECOVERED,
        NO_ACTION,
        CAS_CONFLICT,
        FAILED,
        CANCELLATION_SETTLED,
        CANCELLATION_PENDING_RECONCILIATION,
        CANCELLATION_CAS_CONFLICT,
        CANCELLATION_FAILED
    }

    public enum CasOperation {
        RECOVERY,
        CALLBACK,
        CANCELLATION,
        BILLING
    }

    public enum LateCallbackResult {
        REJECTED_TERMINAL_RUN,
        REJECTED_GENERATION,
        REJECTED_CLAIM,
        RECORDED_PROVIDER_COST
    }

    public enum BillingState {
        RESERVED,
        CAPTURED,
        RELEASED,
        LOST
    }

    public enum CostAlertResult {
        DELIVERED,
        FAILED,
        SUPPRESSED
    }
}
