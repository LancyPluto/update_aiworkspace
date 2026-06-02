package com.aiminilab.aitoolmarket.agent.dto;

public record ModelVendorAccountTestResponse(
        boolean success,
        String message,
        Long latencyMs,
        String provider,
        String modelName,
        ModelVendorAccountResponse account
) {
}
