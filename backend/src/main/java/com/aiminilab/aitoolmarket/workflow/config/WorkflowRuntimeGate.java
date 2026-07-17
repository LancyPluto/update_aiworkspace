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

    public Decision evaluateNewRun(Long userId,
                                   boolean toolExecutionEnabled,
                                   boolean paidRun,
                                   long estimatedRunCredits,
                                   long userDailyCredits) {
        Decision baseDecision = evaluateBaseNewRun(userId, toolExecutionEnabled);
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
        if (properties.getMaxRunCostCredits() <= 0
                || estimatedRunCredits > properties.getMaxRunCostCredits()) {
            return Decision.denied("run_cost_limit_exceeded");
        }
        if (userDailyCredits < 0
                || properties.getMaxUserDailyCostCredits() <= 0
                || userDailyCredits > (long) properties.getMaxUserDailyCostCredits() - estimatedRunCredits) {
            return Decision.denied("user_daily_cost_limit_exceeded");
        }
        return Decision.allowedDecision();
    }

    public Decision evaluateBaseNewRun(Long userId, boolean toolExecutionEnabled) {
        if (!properties.isEnabled()) {
            return Decision.denied("runtime_disabled");
        }
        if (!properties.isExecutionEnabled()) {
            return Decision.denied("execution_disabled");
        }
        if (!toolExecutionEnabled) {
            return Decision.denied("tool_execution_disabled");
        }
        if (!reconciliationHealthy) {
            return Decision.denied("reconciliation_not_healthy");
        }
        if (!isCanaryUser(userId)) {
            return Decision.denied("user_not_in_canary");
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

    private boolean isCanaryUser(Long userId) {
        if (userId == null) {
            return false;
        }
        if (properties.getAllowedUserIds().contains(userId)) {
            return true;
        }
        int percentage = properties.getCanaryPercentage();
        if (percentage < 0 || percentage > 100) {
            return false;
        }
        return percentage > 0 && Math.floorMod(userId, 100L) < percentage;
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
