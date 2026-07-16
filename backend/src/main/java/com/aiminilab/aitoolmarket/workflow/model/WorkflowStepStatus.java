package com.aiminilab.aitoolmarket.workflow.model;

public enum WorkflowStepStatus {
    PENDING,
    READY,
    QUEUED,
    RUNNING,
    AWAITING_USER,
    SUCCESS,
    FAILED,
    CANCELLED
}
