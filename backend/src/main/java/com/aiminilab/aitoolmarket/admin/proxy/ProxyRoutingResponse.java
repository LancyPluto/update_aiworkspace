package com.aiminilab.aitoolmarket.admin.proxy;

import java.util.List;

public record ProxyRoutingResponse(
        List<ProxyRoutingRule> rules,
        ProxyAutoSettings autoSettings,
        String fallbackStrategy,
        List<String> warnings
) {
}
