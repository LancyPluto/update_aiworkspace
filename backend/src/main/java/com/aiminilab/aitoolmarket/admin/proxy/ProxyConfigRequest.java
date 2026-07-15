package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyConfigRequest(
        boolean enabled,
        String sourceType,
        String displayName,
        String subscriptionUrl,
        Integer subscriptionUpdateIntervalMinutes,
        String mihomoEndpoint,
        String manualProtocol,
        String manualHost,
        Integer manualPort,
        String manualUsername,
        String manualPassword,
        String noProxyHosts
) {
}
