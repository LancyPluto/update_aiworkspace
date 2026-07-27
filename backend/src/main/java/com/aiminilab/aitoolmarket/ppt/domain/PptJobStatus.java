package com.aiminilab.aitoolmarket.ppt.domain;

public enum PptJobStatus {
    CREATED,
    CREDIT_RESERVED,
    SUBMITTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RECONCILING;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
