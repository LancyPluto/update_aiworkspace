package com.aiminilab.aitoolmarket.workflow.model;

public enum WorkflowStepStatus {
    PENDING,
    READY,
    QUEUED,
    RUNNING,
    AWAITING_USER,
    AWAITING_FUNDS,
    SUCCESS,
    FAILED,
    CANCELLED
}
