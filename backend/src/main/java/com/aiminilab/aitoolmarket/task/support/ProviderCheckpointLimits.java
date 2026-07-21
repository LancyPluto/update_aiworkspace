package com.aiminilab.aitoolmarket.task.support;

public final class ProviderCheckpointLimits {

    public static final int MAX_PERSISTED_BYTES = 1024 * 1024;
    public static final int MAX_REQUEST_BODY_BYTES = 2 * MAX_PERSISTED_BYTES;

    private ProviderCheckpointLimits() {
    }
}
