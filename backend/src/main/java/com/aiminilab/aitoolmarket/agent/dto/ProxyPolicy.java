package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingRule;

import java.util.List;

public record ProxyPolicy(
        String mode,
        String proxyUrl,
        Boolean enabled,
        List<String> noProxyHosts,
        List<ProxyRoutingRule> routingRules,
        String businessFallback,
        String projectProxyUrl,
        Boolean routingEnabled
) {
}
