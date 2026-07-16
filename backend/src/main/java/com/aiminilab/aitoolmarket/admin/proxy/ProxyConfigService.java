package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.AgentOutboundProxySettings;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.common.util.UrlSecurityValidator;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ProxyConfigService {
    public static final String SOURCE_SUBSCRIPTION = "SUBSCRIPTION";
    public static final String SOURCE_MANUAL = "MANUAL";
    private static final Set<String> MANUAL_PROTOCOLS = Set.of("HTTP", "HTTPS", "SOCKS5");

    private static final String SOURCE_TYPE_KEY = "outbound.proxy.sourceType";
    private static final String DISPLAY_NAME_KEY = "outbound.proxy.displayName";
    private static final String SUBSCRIPTION_URL_KEY = "outbound.proxy.subscriptionUrl";
    private static final String SUBSCRIPTION_INTERVAL_KEY = "outbound.proxy.subscriptionUpdateIntervalMinutes";
    private static final String MIHOMO_ENDPOINT_KEY = "outbound.proxy.mihomoEndpoint";
    private static final String MANUAL_PROTOCOL_KEY = "outbound.proxy.manualProtocol";
    private static final String MANUAL_HOST_KEY = "outbound.proxy.manualHost";
    private static final String MANUAL_PORT_KEY = "outbound.proxy.manualPort";
    private static final String MANUAL_USERNAME_KEY = "outbound.proxy.manualUsername";
    private static final String MANUAL_PASSWORD_KEY = "outbound.proxy.manualPassword";
    private static final String ROUTING_ENABLED_KEY = "outbound.proxy.routingEnabled";

    private static final String DEFAULT_MIHOMO_ENDPOINT = "http://host.docker.internal:7890";
    private static final String PROJECT_MIHOMO_ENDPOINT = "http://mihomo:7890";
    private static final int DEFAULT_SUBSCRIPTION_INTERVAL = 360;
    private static final int DEFAULT_MANUAL_PORT = 7890;

    private final SystemSettingService systemSettingService;
    private final ProxyConnectionProbe connectionProbe;

    public ProxyConfigService(SystemSettingService systemSettingService, ProxyConnectionProbe connectionProbe) {
        this.systemSettingService = systemSettingService;
        this.connectionProbe = connectionProbe;
    }

    public ProxyConfigResponse getConfig() {
        return toResponse(systemSettingService.settings());
    }

    public ProxyConfigResponse update(ProxyConfigRequest request, Long operatorId) {
        if (request == null) {
            throw paramError("代理配置不能为空");
        }
        Map<String, String> current = new LinkedHashMap<>(systemSettingService.settings());
        String sourceType = normalizeSourceType(request.sourceType());
        String displayName = required(request.displayName(), "请输入配置名称");
        String noProxyHosts = required(request.noProxyHosts(), "请输入直连域名或主机列表");

        String subscriptionUrl = preserveSecret(request.subscriptionUrl(), current.get(SUBSCRIPTION_URL_KEY));
        String manualPassword = preserveSecret(request.manualPassword(), current.get(MANUAL_PASSWORD_KEY));
        String mihomoEndpoint = defaultIfBlank(request.mihomoEndpoint(), DEFAULT_MIHOMO_ENDPOINT);
        int subscriptionInterval = boundedInt(request.subscriptionUpdateIntervalMinutes(), DEFAULT_SUBSCRIPTION_INTERVAL, 15, 10_080,
                "订阅更新间隔需在 15 到 10080 分钟之间");
        String manualProtocol = normalizeProtocol(request.manualProtocol());
        String manualHost = trim(request.manualHost());
        int manualPort = boundedInt(request.manualPort(), DEFAULT_MANUAL_PORT, 1, 65_535,
                "代理端口需在 1 到 65535 之间");
        String manualUsername = preserveSecret(request.manualUsername(), current.get(MANUAL_USERNAME_KEY));

        validateProxyEndpoint(mihomoEndpoint);
        if (SOURCE_SUBSCRIPTION.equals(sourceType)) {
            validateSubscriptionUrl(required(subscriptionUrl, "请输入机场订阅地址"));
        } else {
            validatePublicIp(manualHost);
        }

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put(SOURCE_TYPE_KEY, sourceType);
        saved.put(DISPLAY_NAME_KEY, displayName);
        saved.put(SUBSCRIPTION_URL_KEY, subscriptionUrl);
        saved.put(SUBSCRIPTION_INTERVAL_KEY, String.valueOf(subscriptionInterval));
        saved.put(MIHOMO_ENDPOINT_KEY, mihomoEndpoint);
        saved.put(MANUAL_PROTOCOL_KEY, manualProtocol);
        saved.put(MANUAL_HOST_KEY, manualHost);
        saved.put(MANUAL_PORT_KEY, String.valueOf(manualPort));
        saved.put(MANUAL_USERNAME_KEY, manualUsername);
        saved.put(MANUAL_PASSWORD_KEY, manualPassword);
        saved.put(AgentOutboundProxySettings.PROXY_URL_KEY, "");
        saved.put(AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "false");
        saved.put(ROUTING_ENABLED_KEY, String.valueOf(request.enabled()));
        saved.put(AgentOutboundProxySettings.NO_PROXY_HOSTS_KEY, noProxyHosts);
        systemSettingService.updateSettings(saved, operatorId);

        current.putAll(saved);
        return toResponse(current);
    }

    public ProxyTestResponse testConnection() {
        Map<String, String> settings = systemSettingService.settings();
        String sourceType = normalizeSourceType(settings.get(SOURCE_TYPE_KEY));
        if (SOURCE_SUBSCRIPTION.equals(sourceType)) {
            String subscriptionUrl = required(settings.get(SUBSCRIPTION_URL_KEY), "请先保存机场订阅地址");
            validateSubscriptionUrl(subscriptionUrl);
            return connectionProbe.testSubscription(subscriptionUrl);
        }
        String host = trim(settings.get(MANUAL_HOST_KEY));
        int port = boundedInt(parseInteger(settings.get(MANUAL_PORT_KEY)), DEFAULT_MANUAL_PORT, 1, 65_535,
                "代理端口需在 1 到 65535 之间");
        validatePublicIp(host);
        return connectionProbe.testManual(host, port);
    }

    private ProxyConfigResponse toResponse(Map<String, String> settings) {
        String sourceType = normalizeSourceType(settings.get(SOURCE_TYPE_KEY));
        String subscriptionUrl = trim(settings.get(SUBSCRIPTION_URL_KEY));
        String username = trim(settings.get(MANUAL_USERNAME_KEY));
        String password = trim(settings.get(MANUAL_PASSWORD_KEY));
        String proxyUrl = trim(settings.get(AgentOutboundProxySettings.PROXY_URL_KEY));
        return new ProxyConfigResponse(
                Boolean.parseBoolean(settings.getOrDefault(ROUTING_ENABLED_KEY, "false")),
                sourceType,
                defaultIfBlank(settings.get(DISPLAY_NAME_KEY), "默认代理"),
                !subscriptionUrl.isBlank(),
                maskSubscriptionUrl(subscriptionUrl),
                parseInt(settings.get(SUBSCRIPTION_INTERVAL_KEY), DEFAULT_SUBSCRIPTION_INTERVAL),
                defaultIfBlank(settings.get(MIHOMO_ENDPOINT_KEY), DEFAULT_MIHOMO_ENDPOINT),
                normalizeProtocol(settings.get(MANUAL_PROTOCOL_KEY)),
                trim(settings.get(MANUAL_HOST_KEY)),
                parseInt(settings.get(MANUAL_PORT_KEY), DEFAULT_MANUAL_PORT),
                maskUsername(username),
                !password.isBlank(),
                settings.getOrDefault(AgentOutboundProxySettings.NO_PROXY_HOSTS_KEY,
                        AgentOutboundProxySettings.DEFAULT_NO_PROXY_HOSTS),
                Boolean.parseBoolean(settings.getOrDefault(ROUTING_ENABLED_KEY, "false"))
                        ? PROJECT_MIHOMO_ENDPOINT : ""
        );
    }

    private String normalizeSourceType(String value) {
        String normalized = trim(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return SOURCE_SUBSCRIPTION;
        }
        if (!SOURCE_SUBSCRIPTION.equals(normalized) && !SOURCE_MANUAL.equals(normalized)) {
            throw paramError("代理来源仅支持 SUBSCRIPTION 或 MANUAL");
        }
        return normalized;
    }

    private String normalizeProtocol(String value) {
        String normalized = defaultIfBlank(value, "HTTP").toUpperCase(Locale.ROOT);
        if (!MANUAL_PROTOCOLS.contains(normalized)) {
            throw paramError("云服务器协议仅支持 HTTP、HTTPS 或 SOCKS5");
        }
        return normalized;
    }

    private void validateSubscriptionUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw paramError("机场订阅地址必须是有效的 HTTP/HTTPS URL");
            }
            if (looksLikeIp(uri.getHost())) {
                UrlSecurityValidator.validate(rawUrl);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw paramError("机场订阅地址格式不正确");
        }
    }

    private void validateProxyEndpoint(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme) || "socks5".equals(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw paramError("Mihomo 出口地址必须包含协议、主机和端口");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw paramError("Mihomo 出口地址格式不正确");
        }
    }

    private void validatePublicIp(String host) {
        if (host.isBlank() || !looksLikeIp(host)) {
            throw paramError("请输入云服务器的公网 IP，不支持域名或内网地址");
        }
        try {
            InetAddress.getByName(host);
            String url = host.contains(":") ? "http://[" + host + "]" : "http://" + host;
            UrlSecurityValidator.validate(url);
        } catch (BusinessException exception) {
            throw paramError("请输入可路由的公网 IP，不能使用回环、内网或云元数据地址");
        } catch (Exception exception) {
            throw paramError("请输入有效的公网 IP");
        }
    }

    private String buildManualProxyUrl(String protocol, String host, int port, String username, String password) {
        String userInfo = username.isBlank() ? null : username + (password.isBlank() ? "" : ":" + password);
        try {
            return new URI(protocol.toLowerCase(Locale.ROOT), userInfo, host, port, null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw paramError("云服务器代理参数无法生成有效地址");
        }
    }

    private String maskSubscriptionUrl(String rawUrl) {
        if (rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl);
            String authority = uri.getRawAuthority();
            String origin = uri.getScheme() + "://" + authority;
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return origin + "/***";
            }
            String maskedQuery = String.join("&", java.util.Arrays.stream(query.split("&"))
                    .map(part -> part.contains("=") ? part.substring(0, part.indexOf('=')) + "=***" : "***")
                    .toList());
            return origin + "/***?" + maskedQuery;
        } catch (Exception ignored) {
            return "***";
        }
    }

    private String maskProxyUrl(String rawUrl) {
        if (rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || userInfo.isBlank()) {
                return rawUrl;
            }
            String username = userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
            return new URI(uri.getScheme(), maskUsername(username) + ":***", uri.getHost(), uri.getPort(), null, null, null)
                    .toString();
        } catch (Exception ignored) {
            return "***";
        }
    }

    private String maskUsername(String username) {
        if (username.isBlank()) {
            return "";
        }
        if (username.length() <= 2) {
            return username.substring(0, 1) + "***";
        }
        if (username.length() <= 4) {
            return username.substring(0, 1) + "***" + username.substring(username.length() - 1);
        }
        return username.substring(0, 2) + "***" + username.substring(username.length() - 2);
    }

    private boolean looksLikeIp(String host) {
        return host.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$") || host.contains(":");
    }

    private int boundedInt(Integer value, int fallback, int min, int max, String message) {
        int resolved = value == null ? fallback : value;
        if (resolved < min || resolved > max) {
            throw paramError(message);
        }
        return resolved;
    }

    private Integer parseInteger(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int parseInt(String value, int fallback) {
        Integer parsed = parseInteger(value);
        return parsed == null ? fallback : parsed;
    }

    private String preserveSecret(String incoming, String current) {
        return trim(incoming).isBlank() ? trim(current) : trim(incoming);
    }

    private String required(String value, String message) {
        String normalized = trim(value);
        if (normalized.isBlank()) {
            throw paramError(message);
        }
        return normalized;
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private BusinessException paramError(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }
}
