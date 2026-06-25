package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.AgentOutboundProxySettings;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OutboundProxyPolicyResolver {
    public static final String MODE_INHERIT = "inherit";
    public static final String MODE_ENABLED = "enabled";
    public static final String MODE_DISABLED = "disabled";

    private final SystemSettingService systemSettingService;
    private final ObjectMapper objectMapper;

    public OutboundProxyPolicyResolver(SystemSettingService systemSettingService, ObjectMapper objectMapper) {
        this.systemSettingService = systemSettingService;
        this.objectMapper = objectMapper;
    }

    public ProxyPolicy resolve(AgentModelConfig config) {
        Map<String, String> settings = systemSettingService.settings();
        String defaultUrl = trim(settings.get(AgentOutboundProxySettings.PROXY_URL_KEY));
        boolean enabledByDefault = parseBoolean(
                settings.get(AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY),
                Boolean.parseBoolean(AgentOutboundProxySettings.DEFAULT_ENABLED_BY_DEFAULT)
        );
        List<String> noProxyHosts = splitHosts(
                settings.getOrDefault(
                        AgentOutboundProxySettings.NO_PROXY_HOSTS_KEY,
                        AgentOutboundProxySettings.DEFAULT_NO_PROXY_HOSTS
                )
        );

        JsonNode extra = readObject(config == null ? null : config.getExtraAuthJson());
        String rawMode = normalizeMode(text(extra, "proxyMode"));
        String proxyUrl = text(extra, "proxyUrl");
        if (rawMode.equals(MODE_INHERIT) && !proxyUrl.isBlank()) {
            rawMode = MODE_ENABLED;
        }

        boolean enabled = switch (rawMode) {
            case MODE_ENABLED -> true;
            case MODE_DISABLED -> false;
            default -> enabledByDefault;
        };
        String resolvedUrl = enabled ? firstNonBlank(proxyUrl, defaultUrl) : "";
        if (resolvedUrl.isBlank()) {
            enabled = false;
        }
        return new ProxyPolicy(rawMode, resolvedUrl, enabled, noProxyHosts);
    }

    private JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String key) {
        if (node == null) {
            return "";
        }
        JsonNode child = node.get(key);
        return child == null || child.isNull() ? "" : trim(child.asText(""));
    }

    private String normalizeMode(String mode) {
        String normalized = trim(mode).toLowerCase(Locale.ROOT);
        if (MODE_ENABLED.equals(normalized) || MODE_DISABLED.equals(normalized)) {
            return normalized;
        }
        return MODE_INHERIT;
    }

    private boolean parseBoolean(String value, boolean fallback) {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes") || normalized.equals("on");
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

    private String firstNonBlank(String first, String second) {
        return !trim(first).isBlank() ? trim(first) : trim(second);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
