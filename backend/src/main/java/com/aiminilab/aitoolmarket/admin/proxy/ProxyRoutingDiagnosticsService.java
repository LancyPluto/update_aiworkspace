package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProxyRoutingDiagnosticsService {
    private final ProxyRoutingService routingService;
    private final ProxyPathProbe pathProbe;
    private final Map<String, Deque<Boolean>> history = new ConcurrentHashMap<>();
    private final Map<String, SelectionState> selections = new ConcurrentHashMap<>();

    public ProxyRoutingDiagnosticsService(
            ProxyRoutingService routingService,
            ProxyPathProbe pathProbe
    ) {
        this.routingService = routingService;
        this.pathProbe = pathProbe;
    }

    public ProxyDomainTestResponse test(ProxyDomainTestRequest request, Long operatorId) {
        String domain = normalizeDomain(request == null ? null : request.domain());
        ProxyRoutingConfig config = routingService.currentConfig();
        ProxyRoutingRule matched = match(config.rules(), domain);
        String probeUrl = resolveProbeUrl(request, matched, domain);
        int timeout = config.autoSettings().timeoutMs();

        CompletableFuture<ProxyPathProbeResult> directFuture = CompletableFuture.supplyAsync(
                () -> pathProbe.probe(domain, probeUrl, ProxyEgressPath.DIRECT, timeout)
        );
        CompletableFuture<ProxyPathProbeResult> proxyFuture = CompletableFuture.supplyAsync(
                () -> pathProbe.probe(domain, probeUrl, ProxyEgressPath.PROXY, timeout)
        );
        ProxyPathProbeResult direct = withHistory(domain, directFuture.join(), config.autoSettings().sampleSize());
        ProxyPathProbeResult proxy = withHistory(domain, proxyFuture.join(), config.autoSettings().sampleSize());
        ProxyAutoDecision decision = decide(domain, direct, proxy, config.autoSettings());
        Instant testedAt = Instant.now();
        ProxyDomainTestResponse response = new ProxyDomainTestResponse(
                domain,
                matched == null ? "fallback" : matched.id(),
                matched == null ? "DIRECT" : matched.strategy(),
                "HEAD",
                direct,
                proxy,
                decision,
                testedAt
        );
        if ((matched != null && !"DIRECT".equalsIgnoreCase(matched.strategy())) || proxy.success()) {
            routingService.recordTest(
                    matched == null ? domain : ProxyRoutingService.testResultDomain(matched),
                    proxy.success(),
                    proxy.totalMs(),
                    testedAt,
                    operatorId
            );
        }
        return response;
    }

    private ProxyPathProbeResult withHistory(String domain, ProxyPathProbeResult result, int maxSamples) {
        String key = domain + ":" + result.path();
        Deque<Boolean> samples = history.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addLast(result.success());
            while (samples.size() > maxSamples) {
                samples.removeFirst();
            }
            long successCount = samples.stream().filter(Boolean::booleanValue).count();
            return result.withHistory((double) successCount / samples.size(), samples.size());
        }
    }

    private ProxyAutoDecision decide(
            String domain,
            ProxyPathProbeResult direct,
            ProxyPathProbeResult proxy,
            ProxyAutoSettings settings
    ) {
        String selected;
        String reason;
        if (direct.success() != proxy.success()) {
            selected = proxy.success() ? "PROXY" : "DIRECT";
            reason = "可用性优先：选择当前可用路径";
        } else if (!direct.success()) {
            selected = "DIRECT";
            reason = "双路径均不可用，按业务默认回退 DIRECT";
        } else {
            long difference = Math.abs(direct.totalMs() - proxy.totalMs());
            selected = difference <= settings.switchThresholdMs()
                    ? currentOrDefault(domain)
                    : (direct.totalMs() <= proxy.totalMs() ? "DIRECT" : "PROXY");
            reason = difference <= settings.switchThresholdMs()
                    ? "延迟差未超过切换阈值，保持当前路径"
                    : "双路径可用，选择低延迟路径";
        }
        SelectionState current = selections.get(domain);
        Instant now = Instant.now();
        if (current != null && !current.path().equals(selected)
                && now.isBefore(current.changedAt().plusSeconds(settings.cooldownSeconds()))) {
            boolean currentAvailable = "DIRECT".equals(current.path()) ? direct.success() : proxy.success();
            if (currentAvailable) {
                selected = current.path();
                reason = "仍在冷却时间内，保持当前可用路径";
            }
        }
        selections.put(domain, new SelectionState(selected, current == null || !current.path().equals(selected)
                ? now : current.changedAt()));
        return new ProxyAutoDecision(
                selected,
                reason,
                Math.min(direct.sampleCount(), proxy.sampleCount()),
                settings.timeoutMs(),
                settings.switchThresholdMs(),
                settings.hysteresisMs(),
                settings.cooldownSeconds()
        );
    }

    private String currentOrDefault(String domain) {
        SelectionState state = selections.get(domain);
        return state == null ? "DIRECT" : state.path();
    }

    private String resolveProbeUrl(ProxyDomainTestRequest request, ProxyRoutingRule matched, String domain) {
        String requested = request == null || request.probeUrl() == null ? "" : request.probeUrl().trim();
        String configured = matched == null || matched.probeUrl() == null ? "" : matched.probeUrl().trim();
        String value = !requested.isBlank() ? requested : (!configured.isBlank() ? configured : "https://" + domain + "/");
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            String probeHost = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!probeHost.equals(domain)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "探针地址必须与诊断域名使用同一域名");
            }
            return uri.toASCIIString();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "探针地址必须是有效的 HTTPS URL");
        }
    }

    private ProxyRoutingRule match(List<ProxyRoutingRule> rules, String domain) {
        return ProxyRoutingRules.sorted(rules).stream()
                .filter(ProxyRoutingRule::enabled)
                .filter(rule -> ProxyRoutingRules.matches(rule, domain))
                .findFirst()
                .orElse(null);
    }

    private String normalizeDomain(String rawDomain) {
        try {
            String domain = IDN.toASCII(rawDomain == null ? "" : rawDomain.trim(), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            if (domain.isBlank() || !domain.contains(".") || domain.contains("/") || domain.contains(":")) {
                throw new IllegalArgumentException();
            }
            return domain;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请输入有效的公网域名");
        }
    }

    private record SelectionState(String path, Instant changedAt) {
    }
}
