package com.aiminilab.aitoolmarket.workflow.config;

import org.springframework.stereotype.Component;

@Component
public class WorkflowRuntimeGate {

    private final WorkflowRuntimeProperties properties;
    private volatile boolean reconciliationHealthy;
    private long reconciliationFailureGeneration;

    public WorkflowRuntimeGate(WorkflowRuntimeProperties properties) {
        this.properties = properties;
    }

    public Decision evaluateNewRun(boolean paidRun, long estimatedRunCredits) {
        Decision baseDecision = evaluateBaseNewRun();
        if (!baseDecision.allowed()) {
            return baseDecision;
        }
        if (!paidRun) {
            return Decision.allowedDecision();
        }
        if (properties.isRealBillingEnabled() && properties.isShadowBillingEnabled()) {
            return Decision.denied("billing_mode_conflict");
        }
        if (!properties.isRealBillingEnabled()) {
            return Decision.denied("paid_run_requires_real_billing");
        }
        if (estimatedRunCredits <= 0) {
            return Decision.denied("paid_run_cost_unknown");
        }
        return Decision.allowedDecision();
    }

    public Decision evaluateBaseNewRun() {
        if (!properties.isEnabled()) {
            return Decision.denied("runtime_disabled");
        }
        if (!properties.isExecutionEnabled()) {
            return Decision.denied("execution_disabled");
        }
        return Decision.allowedDecision();
    }

    public boolean isConfirmationEnabled() {
        return properties.isEnabled() && properties.isConfirmationEnabled();
    }

    public boolean isAutoRetryEnabled() {
        return properties.isEnabled() && properties.isAutoRetryEnabled();
    }

    public boolean isReconciliationHealthy() {
        return reconciliationHealthy;
    }

    public synchronized long reconciliationFailureGeneration() {
        return reconciliationFailureGeneration;
    }

    public synchronized boolean markReconciliationHealthyAfterFullScan(long observedFailureGeneration) {
        if (observedFailureGeneration != reconciliationFailureGeneration) {
            return false;
        }
        reconciliationHealthy = true;
        return true;
    }

    public synchronized void markReconciliationUnhealthy() {
        reconciliationHealthy = false;
        reconciliationFailureGeneration++;
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allowedDecision() {
            return new Decision(true, "allowed");
        }

        public static Decision denied(String reason) {
            return new Decision(false, reason);
        }
    }
}
