package com.aiminilab.aitoolmarket.admin.proxy;

import java.util.List;

public record ProxyRoutingConfig(
        List<ProxyRoutingRule> rules,
        ProxyAutoSettings autoSettings
) {
    public ProxyRoutingConfig {
        rules = rules == null ? List.of() : List.copyOf(rules);
        autoSettings = autoSettings == null ? ProxyAutoSettings.defaults() : autoSettings;
    }

    public static ProxyRoutingConfig defaults() {
        return new ProxyRoutingConfig(List.of(), ProxyAutoSettings.defaults());
    }
}
