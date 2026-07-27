package com.aiminilab.aitoolmarket.workflow.support;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;

public final class WorkflowBillingReconciliationState {

    public static final String BILLING_STATUS = "RECONCILIATION_FAILED";
    public static final String ERROR_CODE = "WORKFLOW_BILLING_RECONCILIATION_FAILED";
    public static final String ERROR_MESSAGE = "Workflow billing reconciliation failed";

    private WorkflowBillingReconciliationState() {
    }

    public static boolean isIsolation(WorkflowRun run) {
        return run != null
                && "CANCELLING".equals(run.getStatus())
                && BILLING_STATUS.equals(run.getBillingStatus())
                && ERROR_CODE.equals(run.getErrorCode());
    }
}
