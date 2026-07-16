package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyRoutingRule(
        String id,
        String patternType,
        String pattern,
        String strategy,
        int priority,
        boolean enabled,
        String note,
        String probeUrl
) {
}
