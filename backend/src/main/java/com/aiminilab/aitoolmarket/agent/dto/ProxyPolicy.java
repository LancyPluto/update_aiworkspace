package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record ProxyPolicy(
        String mode,
        String proxyUrl,
        Boolean enabled,
        List<String> noProxyHosts
) {
}
