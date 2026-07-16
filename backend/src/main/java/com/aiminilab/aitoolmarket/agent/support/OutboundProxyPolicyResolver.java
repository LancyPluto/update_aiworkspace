package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoConfigRenderer;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingConfig;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingRule;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingRules;
import com.aiminilab.aitoolmarket.agent.config.AgentOutboundProxySettings;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.URI;

@Component
public class OutboundProxyPolicyResolver {
    public static final String MODE_INHERIT = "inherit";
    public static final String MODE_ENABLED = "enabled";
    public static final String MODE_DISABLED = "disabled";
    public static final String PROJECT_MIHOMO_PROXY_URL = "http://mihomo:7890";

    private final SystemSettingService systemSettingService;
    private final ObjectMapper objectMapper;

    public OutboundProxyPolicyResolver(SystemSettingService systemSettingService, ObjectMapper objectMapper) {
        this.systemSettingService = systemSettingService;
        this.objectMapper = objectMapper;
    }

    public ProxyPolicy resolve(AgentModelConfig config) {
        Map<String, String> settings = systemSettingService.settings();
        List<String> noProxyHosts = splitHosts(
                settings.getOrDefault(
                        AgentOutboundProxySettings.NO_PROXY_HOSTS_KEY,
                        AgentOutboundProxySettings.DEFAULT_NO_PROXY_HOSTS
                )
        );

        ProxyRoutingConfig routing = routingConfig(settings.get(MihomoConfigRenderer.ROUTING_CONFIG_KEY));
        boolean applicationRoutingEnabled = parseBoolean(settings.get("outbound.proxy.routingEnabled"), true);
        List<ProxyRoutingRule> enabledRules = routing.rules().stream()
                .filter(ProxyRoutingRule::enabled)
                .toList();
        ProxyRoutingRule matched = !applicationRoutingEnabled
                ? null
                : match(enabledRules, host(config == null ? null : config.getBaseUrl()));
        String strategy = matched == null ? "DIRECT" : upper(matched.strategy());
        boolean enabled = "PROXY".equals(strategy) || "AUTO".equals(strategy);
        boolean routingEnabled = applicationRoutingEnabled;
        return new ProxyPolicy(
                strategy,
                enabled ? PROJECT_MIHOMO_PROXY_URL : "",
                enabled,
                noProxyHosts,
                enabledRules,
                "DIRECT",
                enabledRules.isEmpty() ? "" : PROJECT_MIHOMO_PROXY_URL,
                routingEnabled
        );
    }

    private ProxyRoutingConfig routingConfig(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return ProxyRoutingConfig.defaults();
        }
        try {
            return objectMapper.readValue(rawJson, ProxyRoutingConfig.class);
        } catch (Exception ignored) {
            return ProxyRoutingConfig.defaults();
        }
    }

    private ProxyRoutingRule match(List<ProxyRoutingRule> rules, String host) {
        if (host.isBlank()) {
            return null;
        }
        return ProxyRoutingRules.sorted(rules).stream()
                .filter(rule -> ProxyRoutingRules.matches(rule, host))
                .findFirst()
                .orElse(null);
    }

    private String host(String rawUrl) {
        try {
            String host = URI.create(trim(rawUrl)).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String upper(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    private List<String> splitHosts(String value) {
        String normalized = trim(value);
        if (normalized.isBlank()) {
            normalized = AgentOutboundProxySettings.DEFAULT_NO_PROXY_HOSTS;
        }
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private boolean parseBoolean(String value, boolean fallback) {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes") || normalized.equals("on");
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
