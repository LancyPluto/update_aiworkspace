package com.aiminilab.aitoolmarket.admin.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class MihomoConfigRenderer {
    private static final String SOURCE_TYPE_KEY = "outbound.proxy.sourceType";
    private static final String SUBSCRIPTION_URL_KEY = "outbound.proxy.subscriptionUrl";
    private static final String SUBSCRIPTION_INTERVAL_KEY = "outbound.proxy.subscriptionUpdateIntervalMinutes";
    private static final String MANUAL_PROTOCOL_KEY = "outbound.proxy.manualProtocol";
    private static final String MANUAL_HOST_KEY = "outbound.proxy.manualHost";
    private static final String MANUAL_PORT_KEY = "outbound.proxy.manualPort";
    private static final String MANUAL_USERNAME_KEY = "outbound.proxy.manualUsername";
    private static final String MANUAL_PASSWORD_KEY = "outbound.proxy.manualPassword";
    private static final String NO_PROXY_HOSTS_KEY = "outbound.proxy.noProxyHosts";
    public static final String ROUTING_CONFIG_KEY = "outbound.proxy.routing";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public String render(Map<String, String> settings, String controllerSecret) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("mixed-port: 7890\n")
                .append("allow-lan: true\n")
                .append("bind-address: '*'\n")
                .append("mode: rule\n")
                .append("log-level: info\n")
                .append("external-controller: '0.0.0.0:9090'\n")
                .append("secret: ").append(quote(controllerSecret)).append("\n\n");

        String sourceType = value(settings, SOURCE_TYPE_KEY, ProxyConfigService.SOURCE_SUBSCRIPTION)
                .toUpperCase(Locale.ROOT);
        if (ProxyConfigService.SOURCE_MANUAL.equals(sourceType)) {
            appendManualNode(yaml, settings);
        } else {
            appendSubscription(yaml, settings);
        }

        ProxyRoutingConfig routing = routingConfig(settings.get(ROUTING_CONFIG_KEY));
        appendProxyGroups(yaml, sourceType, routing);
        appendRules(yaml, routing, settings.get(NO_PROXY_HOSTS_KEY));
        return yaml.toString();
    }

    private void appendSubscription(StringBuilder yaml, Map<String, String> settings) {
        int intervalSeconds = parseInt(settings.get(SUBSCRIPTION_INTERVAL_KEY), 360) * 60;
        yaml.append("proxy-providers:\n")
                .append("  subscription:\n")
                .append("    type: http\n")
                .append("    url: ").append(quote(value(settings, SUBSCRIPTION_URL_KEY, ""))).append("\n")
                .append("    path: './providers/subscription.yaml'\n")
                .append("    interval: ").append(intervalSeconds).append("\n")
                .append("    health-check:\n")
                .append("      enable: true\n")
                .append("      interval: 300\n")
                .append("      url: 'https://www.gstatic.com/generate_204'\n\n");
    }

    private void appendManualNode(StringBuilder yaml, Map<String, String> settings) {
        String protocol = value(settings, MANUAL_PROTOCOL_KEY, "HTTP").toUpperCase(Locale.ROOT);
        String type = "SOCKS5".equals(protocol) ? "socks5" : "http";
        yaml.append("proxies:\n")
                .append("  - name: 'manual-node'\n")
                .append("    type: ").append(quote(type)).append("\n")
                .append("    server: ").append(quote(value(settings, MANUAL_HOST_KEY, ""))).append("\n")
                .append("    port: ").append(parseInt(settings.get(MANUAL_PORT_KEY), 7890)).append("\n");
        if ("HTTPS".equals(protocol)) {
            yaml.append("    tls: true\n");
        }
        appendOptional(yaml, "username", settings.get(MANUAL_USERNAME_KEY));
        appendOptional(yaml, "password", settings.get(MANUAL_PASSWORD_KEY));
        yaml.append("\n");
    }

    private void appendProxyGroups(StringBuilder yaml, String sourceType, ProxyRoutingConfig routing) {
        yaml.append("proxy-groups:\n")
                .append("  - name: 'PROXY'\n")
                .append("    type: select\n");
        if (ProxyConfigService.SOURCE_MANUAL.equals(sourceType)) {
            yaml.append("    proxies:\n")
                    .append("      - 'manual-node'\n");
        } else {
            yaml.append("    use:\n")
                    .append("      - 'subscription'\n");
        }
        for (ProxyRoutingRule rule : sortedRules(routing)) {
            if (!"AUTO".equalsIgnoreCase(rule.strategy())) {
                continue;
            }
            yaml.append("  - name: ").append(quote(autoGroupName(rule))).append("\n")
                    .append("    type: url-test\n")
                    .append("    proxies:\n")
                    .append("      - 'DIRECT'\n")
                    .append("      - 'PROXY'\n")
                    .append("    url: ").append(quote(rule.probeUrl())).append("\n")
                    .append("    interval: ").append(Math.max(30, routing.autoSettings().cooldownSeconds())).append("\n")
                    .append("    tolerance: ").append(Math.max(0, routing.autoSettings().hysteresisMs())).append("\n")
                    .append("    lazy: false\n");
        }
        yaml.append("\n");
    }

    private void appendRules(StringBuilder yaml, ProxyRoutingConfig routing, String noProxyHosts) {
        yaml.append("rules:\n");
        for (ProxyRoutingRule rule : sortedRules(routing)) {
            yaml.append("  - ").append(quote(toRoutingRule(rule))).append("\n");
        }
        Set<String> rules = new LinkedHashSet<>();
        if (noProxyHosts != null) {
            for (String rawHost : noProxyHosts.split("[,\\r\\n]+")) {
                String host = rawHost.trim();
                if (!host.isBlank()) {
                    rules.add(toDirectRule(host));
                }
            }
        }
        rules.stream().filter(rule -> !rule.isBlank())
                .forEach(rule -> yaml.append("  - ").append(quote(rule)).append("\n"));
        yaml.append("  - 'MATCH,DIRECT'\n");
    }

    private List<ProxyRoutingRule> sortedRules(ProxyRoutingConfig routing) {
        List<ProxyRoutingRule> rules = new ArrayList<>(routing.rules().stream()
                .filter(ProxyRoutingRule::enabled)
                .toList());
        return ProxyRoutingRules.sorted(rules);
    }

    private String toRoutingRule(ProxyRoutingRule rule) {
        String type = switch (normalized(rule.patternType()).toUpperCase(Locale.ROOT)) {
            case "EXACT" -> "DOMAIN";
            case "WILDCARD" -> "DOMAIN-WILDCARD";
            default -> "DOMAIN-SUFFIX";
        };
        String strategy = "AUTO".equalsIgnoreCase(rule.strategy())
                ? autoGroupName(rule)
                : normalized(rule.strategy()).toUpperCase(Locale.ROOT);
        return type + "," + normalized(rule.pattern()) + "," + strategy;
    }

    private String autoGroupName(ProxyRoutingRule rule) {
        String safeId = normalized(rule.id()).replaceAll("[^A-Za-z0-9_-]", "-");
        return "AUTO-" + (safeId.isBlank() ? "rule" : safeId);
    }

    private ProxyRoutingConfig routingConfig(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return ProxyRoutingConfig.defaults();
        }
        try {
            return OBJECT_MAPPER.readValue(rawJson, ProxyRoutingConfig.class);
        } catch (Exception ignored) {
            return ProxyRoutingConfig.defaults();
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String toDirectRule(String host) {
        if (host.contains("/")) {
            return (host.contains(":") ? "IP-CIDR6," : "IP-CIDR,") + host + ",DIRECT,no-resolve";
        }
        if (host.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) {
            return "IP-CIDR," + host + "/32,DIRECT,no-resolve";
        }
        if (host.contains(":")) {
            return "IP-CIDR6," + host + "/128,DIRECT,no-resolve";
        }
        if (host.startsWith("*.")) {
            return "DOMAIN-SUFFIX," + host.substring(2) + ",DIRECT";
        }
        if (host.startsWith(".")) {
            return "DOMAIN-SUFFIX," + host.substring(1) + ",DIRECT";
        }
        return "DOMAIN," + host + ",DIRECT";
    }

    private void appendOptional(StringBuilder yaml, String key, String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (!trimmed.isBlank()) {
            yaml.append("    ").append(key).append(": ").append(quote(trimmed)).append("\n");
        }
    }

    private String quote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "''") + "'";
    }

    private String value(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int parseInt(String rawValue, int fallback) {
        try {
            int parsed = Integer.parseInt(rawValue);
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
