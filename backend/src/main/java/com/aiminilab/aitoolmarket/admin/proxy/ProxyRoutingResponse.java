package com.aiminilab.aitoolmarket.admin.proxy;

import java.util.List;
import java.util.Map;

public record ProxyRoutingResponse(
        List<ProxyRoutingRule> rules,
        ProxyAutoSettings autoSettings,
        String fallbackStrategy,
        List<String> warnings,
        Map<String, ProxyDomainTestSummary> testResults
) {
}
