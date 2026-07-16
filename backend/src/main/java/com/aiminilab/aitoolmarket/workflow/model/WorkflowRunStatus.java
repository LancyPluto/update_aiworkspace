package com.aiminilab.aitoolmarket.workflow.model;

public enum WorkflowRunStatus {
    RUNNING,
    AWAITING_USER,
    AWAITING_FUNDS,
    CANCELLING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED
}
