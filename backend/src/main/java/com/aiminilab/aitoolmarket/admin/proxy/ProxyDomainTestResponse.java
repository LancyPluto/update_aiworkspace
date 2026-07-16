package com.aiminilab.aitoolmarket.admin.proxy;

import java.time.Instant;

public record ProxyDomainTestResponse(
        String domain,
        String matchedRuleId,
        String matchedStrategy,
        String probeMethod,
        ProxyPathProbeResult direct,
        ProxyPathProbeResult proxy,
        ProxyAutoDecision autoDecision,
        Instant testedAt
) {
}
