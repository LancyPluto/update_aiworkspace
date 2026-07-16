package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.AgentOutboundProxySettings;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ProxyRoutingService {
    private static final Set<String> PATTERN_TYPES = Set.of("EXACT", "SUFFIX", "WILDCARD");
    private static final Set<String> STRATEGIES = Set.of("DIRECT", "PROXY", "AUTO");
    private static final int MAX_RULES = 100;

    private final SystemSettingService systemSettingService;
    private final ObjectMapper objectMapper;

    public ProxyRoutingService(SystemSettingService systemSettingService, ObjectMapper objectMapper) {
        this.systemSettingService = systemSettingService;
        this.objectMapper = objectMapper;
    }

    public ProxyRoutingResponse getConfig() {
        Map<String, String> settings = systemSettingService.settings();
        ProxyRoutingConfig config = readConfig(settings.get(MihomoConfigRenderer.ROUTING_CONFIG_KEY));
        return response(config, settings);
    }

    public ProxyRoutingResponse update(ProxyRoutingUpdateRequest request, Long operatorId) {
        if (request == null) {
            throw paramError("公网分流配置不能为空");
        }
        ProxyRoutingConfig config = normalize(request);
        Map<String, String> current = systemSettingService.settings();
        try {
            Map<String, String> saved = new LinkedHashMap<>();
            saved.put(MihomoConfigRenderer.ROUTING_CONFIG_KEY, objectMapper.writeValueAsString(config));
            saved.put("outbound.proxy.routingEnabled", String.valueOf(config.rules().stream()
                    .anyMatch(rule -> rule.enabled() && !"DIRECT".equalsIgnoreCase(rule.strategy()))));
            saved.put(AgentOutboundProxySettings.PROXY_URL_KEY, "");
            saved.put(AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "false");
            systemSettingService.updateSettings(saved, operatorId);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "公网分流配置无法序列化");
        }
        return response(config, current, legacySocksConfigured(current) ? "历史全局 SOCKS 配置已迁移，业务任务不再直接读取上游地址" : null);
    }

    private ProxyRoutingConfig normalize(ProxyRoutingUpdateRequest request) {
        List<ProxyRoutingRule> incoming = request.rules() == null ? List.of() : request.rules();
        if (incoming.size() > MAX_RULES) {
            throw paramError("公网域名分流规则不能超过 " + MAX_RULES + " 条");
        }
        Set<String> ids = new HashSet<>();
        List<ProxyRoutingRule> normalized = new ArrayList<>();
        for (ProxyRoutingRule rule : incoming) {
            if (rule == null) {
                throw paramError("公网域名分流规则不能为空");
            }
            String id = trim(rule.id());
            if (!id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(id)) {
                throw paramError("规则 ID 必须唯一，且仅包含字母、数字、下划线或连字符");
            }
            String patternType = upper(rule.patternType());
            if (!PATTERN_TYPES.contains(patternType)) {
                throw paramError("域名模式仅支持 EXACT、SUFFIX 或 WILDCARD");
            }
            String pattern = normalizePattern(rule.pattern(), patternType);
            String strategy = upper(rule.strategy());
            if (!STRATEGIES.contains(strategy)) {
                throw paramError("出站策略仅支持 DIRECT、PROXY 或 AUTO");
            }
            if (rule.priority() < 0 || rule.priority() > 10_000) {
                throw paramError("规则优先级必须在 0 到 10000 之间");
            }
            String note = trim(rule.note());
            if (note.length() > 200) {
                throw paramError("规则备注不能超过 200 个字符");
            }
            String probeUrl = trim(rule.probeUrl());
            if ("AUTO".equals(strategy)) {
                validateProbeUrl(probeUrl, patternType, pattern);
            } else {
                probeUrl = "";
            }
            normalized.add(new ProxyRoutingRule(
                    id, patternType, pattern, strategy, rule.priority(), rule.enabled(), note, probeUrl
            ));
        }
        normalized.sort(ruleComparator());
        return new ProxyRoutingConfig(normalized, normalizeAutoSettings(request.autoSettings()));
    }

    private ProxyAutoSettings normalizeAutoSettings(ProxyAutoSettings settings) {
        ProxyAutoSettings value = settings == null ? ProxyAutoSettings.defaults() : settings;
        if (value.timeoutMs() < 500 || value.timeoutMs() > 30_000) {
            throw paramError("AUTO 探针超时必须在 500 到 30000 毫秒之间");
        }
        if (value.sampleSize() < 2 || value.sampleSize() > 50) {
            throw paramError("AUTO 样本数必须在 2 到 50 之间");
        }
        if (value.switchThresholdMs() < 0 || value.switchThresholdMs() > 10_000
                || value.hysteresisMs() < 0 || value.hysteresisMs() > 10_000) {
            throw paramError("AUTO 切换阈值和滞回必须在 0 到 10000 毫秒之间");
        }
        if (value.cooldownSeconds() < 30 || value.cooldownSeconds() > 86_400) {
            throw paramError("AUTO 冷却时间必须在 30 到 86400 秒之间");
        }
        return value;
    }

    private String normalizePattern(String rawPattern, String patternType) {
        String value = trim(rawPattern).toLowerCase(Locale.ROOT);
        if ("WILDCARD".equals(patternType)) {
            if (!value.startsWith("*.") || value.indexOf('*', 1) >= 0) {
                throw paramError("通配域名必须使用 *.example.com 格式");
            }
            return "*." + normalizeDomain(value.substring(2));
        }
        if (value.contains("*")) {
            throw paramError("精确或后缀域名不能包含通配符");
        }
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        return normalizeDomain(value);
    }

    private String normalizeDomain(String domain) {
        try {
            String ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.length() > 253 || !ascii.contains(".") || ascii.startsWith("-") || ascii.endsWith("-")) {
                throw paramError("请输入有效的公网域名模式");
            }
            for (String label : ascii.split("\\.")) {
                if (label.isBlank() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    throw paramError("请输入有效的公网域名模式");
                }
            }
            return ascii;
        } catch (IllegalArgumentException exception) {
            throw paramError("请输入有效的公网域名模式");
        }
    }

    private void validateProbeUrl(String rawUrl, String patternType, String pattern) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost() == null
                    ? ""
                    : IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isBlank()
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || "localhost".equals(host) || host.endsWith(".local") || looksLikeIp(host)) {
                throw paramError("AUTO 规则必须配置独立的公网 HTTPS probe URL");
            }
            ProxyRoutingRule probeRule = new ProxyRoutingRule(
                    "probe-validation", patternType, pattern, "AUTO", 0, true, "", rawUrl
            );
            if (!ProxyRoutingRules.matches(probeRule, host)) {
                throw paramError("AUTO probe URL 域名必须命中当前分流规则");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw paramError("AUTO 规则必须配置独立的公网 HTTPS probe URL");
        }
    }

    private ProxyRoutingResponse response(ProxyRoutingConfig config, Map<String, String> settings) {
        return response(config, settings, legacySocksConfigured(settings)
                ? "检测到历史全局 SOCKS 配置，保存规则后将安全迁移" : null);
    }

    private ProxyRoutingResponse response(ProxyRoutingConfig config, Map<String, String> settings, String migrationWarning) {
        List<String> warnings = new ArrayList<>(conflictWarnings(
                config.rules(), settings.get(AgentOutboundProxySettings.NO_PROXY_HOSTS_KEY)
        ));
        if (migrationWarning != null) {
            warnings.add(migrationWarning);
        }
        return new ProxyRoutingResponse(
                config.rules(),
                config.autoSettings(),
                "DIRECT",
                List.copyOf(warnings)
        );
    }

    private boolean legacySocksConfigured(Map<String, String> settings) {
        String value = settings.getOrDefault(AgentOutboundProxySettings.PROXY_URL_KEY, "").trim().toLowerCase(Locale.ROOT);
        return value.startsWith("socks5://") || value.startsWith("socks5h://");
    }

    private List<String> conflictWarnings(List<ProxyRoutingRule> rules, String noProxyHosts) {
        List<String> bypasses = splitHosts(noProxyHosts);
        List<String> warnings = new ArrayList<>();
        for (ProxyRoutingRule rule : rules) {
            if (!rule.enabled() || "DIRECT".equals(rule.strategy())) {
                continue;
            }
            String base = rule.pattern().startsWith("*.") ? rule.pattern().substring(2) : rule.pattern();
            if (bypasses.stream().anyMatch(host -> overlaps(host, base))) {
                warnings.add("NO_PROXY 将绕过 Mihomo，规则 " + rule.id() + "（" + rule.pattern() + "）不会命中");
            }
        }
        return List.copyOf(warnings);
    }

    private List<String> splitHosts(String rawHosts) {
        if (rawHosts == null || rawHosts.isBlank()) {
            return List.of();
        }
        List<String> hosts = new ArrayList<>();
        for (String raw : rawHosts.split("[,\\r\\n]+")) {
            String host = trim(raw).toLowerCase(Locale.ROOT);
            if (host.startsWith("*.")) {
                host = host.substring(2);
            } else if (host.startsWith(".")) {
                host = host.substring(1);
            }
            if (!host.isBlank()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private boolean overlaps(String noProxyHost, String ruleDomain) {
        return noProxyHost.equals(ruleDomain)
                || noProxyHost.endsWith("." + ruleDomain)
                || ruleDomain.endsWith("." + noProxyHost);
    }

    private ProxyRoutingConfig readConfig(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return ProxyRoutingConfig.defaults();
        }
        try {
            ProxyRoutingConfig config = objectMapper.readValue(rawJson, ProxyRoutingConfig.class);
            return new ProxyRoutingConfig(config.rules().stream().sorted(ruleComparator()).toList(), config.autoSettings());
        } catch (Exception exception) {
            return ProxyRoutingConfig.defaults();
        }
    }

    private Comparator<ProxyRoutingRule> ruleComparator() {
        return ProxyRoutingRules.comparator();
    }

    private String upper(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean looksLikeIp(String host) {
        return host.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$") || host.contains(":");
    }

    private BusinessException paramError(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }
}
