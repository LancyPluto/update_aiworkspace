package com.aiminilab.aitoolmarket.workflow.model;

public record WorkflowChargeReconciliation(
        Long runId,
        int capturedCredits,
        int usageCredits,
        int creditLogDeductions,
        int reservedCharges,
        int lostAttempts,
        int invalidCreditTransitions,
        int providerCostReservationOverruns,
        boolean consistent
) {
}
