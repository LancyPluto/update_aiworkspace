package com.aiminilab.aitoolmarket.agent.config;

import java.util.Map;

public final class AgentOutboundProxySettings {
    public static final String PROXY_URL_KEY = "outbound.proxy.url";
    public static final String ENABLED_BY_DEFAULT_KEY = "outbound.proxy.enabledByDefault";
    public static final String NO_PROXY_HOSTS_KEY = "outbound.proxy.noProxyHosts";

    public static final String DEFAULT_PROXY_URL = "";
    public static final String DEFAULT_ENABLED_BY_DEFAULT = "false";
    public static final String DEFAULT_NO_PROXY_HOSTS = "localhost,127.0.0.1,::1,0.0.0.0,backend,host.docker.internal";

    private AgentOutboundProxySettings() {
    }

    public static Map<String, String> defaults() {
        return Map.of(
                PROXY_URL_KEY, DEFAULT_PROXY_URL,
                ENABLED_BY_DEFAULT_KEY, DEFAULT_ENABLED_BY_DEFAULT,
                NO_PROXY_HOSTS_KEY, DEFAULT_NO_PROXY_HOSTS
        );
    }
}
