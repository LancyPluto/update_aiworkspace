package com.aiminilab.aitoolmarket.admin.proxy;

import java.util.List;

public record ProxyRoutingUpdateRequest(
        List<ProxyRoutingRule> rules,
        ProxyAutoSettings autoSettings
) {
}
