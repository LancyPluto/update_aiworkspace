package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyConfigResponse(
        boolean enabled,
        String sourceType,
        String displayName,
        boolean subscriptionConfigured,
        String subscriptionUrlMasked,
        int subscriptionUpdateIntervalMinutes,
        String mihomoEndpoint,
        String manualProtocol,
        String manualHost,
        int manualPort,
        String manualUsernameMasked,
        boolean manualPasswordConfigured,
        String noProxyHosts,
        String proxyUrlMasked
) {
}
