package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyTestResponse(
        boolean success,
        long latencyMs,
        String message,
        String checkedTarget
) {
}
