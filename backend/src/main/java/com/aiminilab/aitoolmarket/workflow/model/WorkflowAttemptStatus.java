package com.aiminilab.aitoolmarket.workflow.model;

public enum WorkflowAttemptStatus {
    CREATED,
    DISPATCHED,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    LOST,
    CANCELLED,
    SUPERSEDED
}
