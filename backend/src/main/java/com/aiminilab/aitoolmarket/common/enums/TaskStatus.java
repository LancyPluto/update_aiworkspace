package com.aiminilab.aitoolmarket.common.enums;

public enum TaskStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    AWAITING_USER,
    AWAITING_FUNDS,
    RETRYING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED
}
