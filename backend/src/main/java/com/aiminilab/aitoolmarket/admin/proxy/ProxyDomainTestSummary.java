package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyDomainTestSummary(
        boolean success,
        long latencyMs,
        String testedAt
) {
}
