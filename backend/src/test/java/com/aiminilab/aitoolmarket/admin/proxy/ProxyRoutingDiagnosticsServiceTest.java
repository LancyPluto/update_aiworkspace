package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyRoutingDiagnosticsServiceTest {

    @Test
    void autoPrefersAvailablePathThenLatencyWithoutSendingBusinessRequest() {
        MutableSettings settings = settings(Map.of(
                MihomoConfigRenderer.ROUTING_CONFIG_KEY,
                "{\"rules\":[{\"id\":\"ofox\",\"patternType\":\"EXACT\",\"pattern\":\"api.ofox.ai\",\"strategy\":\"AUTO\",\"priority\":100,\"enabled\":true,\"note\":\"\",\"probeUrl\":\"https://api.ofox.ai/health\"}],\"autoSettings\":{\"timeoutMs\":5000,\"sampleSize\":6,\"switchThresholdMs\":150,\"hysteresisMs\":80,\"cooldownSeconds\":300}}"
        ));
        ProxyPathProbe probe = (domain, probeUrl, path, timeoutMs) -> path == ProxyEgressPath.DIRECT
                ? result(path, false, 90, "network unreachable")
                : result(path, true, 220, "");
        ProxyRoutingService routingService = new ProxyRoutingService(settings, new ObjectMapper());
        ProxyRoutingDiagnosticsService service = new ProxyRoutingDiagnosticsService(routingService, probe);

        ProxyDomainTestResponse response = service.test(new ProxyDomainTestRequest("api.ofox.ai", ""), 42L);

        assertThat(response.domain()).isEqualTo("api.ofox.ai");
        assertThat(response.matchedRuleId()).isEqualTo("ofox");
        assertThat(response.direct().success()).isFalse();
        assertThat(response.proxy().success()).isTrue();
        assertThat(response.autoDecision().selectedPath()).isEqualTo("PROXY");
        assertThat(response.autoDecision().reason()).contains("可用性");
        assertThat(response.probeMethod()).isEqualTo("HEAD");
        ProxyDomainTestSummary summary = routingService.getConfig().testResults().get("api.ofox.ai");
        assertThat(summary.success()).isTrue();
        assertThat(summary.latencyMs()).isEqualTo(220);
        assertThat(summary.testedAt()).isNotBlank();
        assertThat(settings.values.get(ProxyRoutingService.ROUTING_TEST_RESULTS_KEY))
                .doesNotContain("network unreachable");
    }

    @Test
    void probeUrlMustTargetTheSameDomainAsStageMeasurements() {
        ProxyRoutingService routingService = new ProxyRoutingService(settings(Map.of()), new ObjectMapper());
        ProxyRoutingDiagnosticsService service = new ProxyRoutingDiagnosticsService(
                routingService,
                (domain, probeUrl, path, timeoutMs) -> result(path, true, 20, "")
        );

        assertThatThrownBy(() -> service.test(new ProxyDomainTestRequest(
                "api.ofox.ai", "https://health.example.com/ping"
        ), 42L
        )).isInstanceOf(com.aiminilab.aitoolmarket.common.exception.BusinessException.class)
                .hasMessageContaining("同一域名");
    }

    @Test
    void failedProxyTestPersistsStatusWithoutFailureReason() {
        MutableSettings settings = settings(Map.of(
                MihomoConfigRenderer.ROUTING_CONFIG_KEY,
                "{\"rules\":[{\"id\":\"ofox\",\"patternType\":\"EXACT\",\"pattern\":\"api.ofox.ai\",\"strategy\":\"PROXY\",\"priority\":100,\"enabled\":true,\"note\":\"\",\"probeUrl\":\"\"}],\"autoSettings\":{\"timeoutMs\":5000,\"sampleSize\":6,\"switchThresholdMs\":150,\"hysteresisMs\":80,\"cooldownSeconds\":300}}"
        ));
        ProxyRoutingService routingService = new ProxyRoutingService(settings, new ObjectMapper());
        ProxyRoutingDiagnosticsService service = new ProxyRoutingDiagnosticsService(
                routingService,
                (domain, probeUrl, path, timeoutMs) -> path == ProxyEgressPath.PROXY
                        ? result(path, false, 5000, "ttl expired")
                        : result(path, true, 80, "")
        );

        service.test(new ProxyDomainTestRequest("api.ofox.ai", ""), 42L);

        ProxyDomainTestSummary summary = routingService.getConfig().testResults().get("api.ofox.ai");
        assertThat(summary.success()).isFalse();
        assertThat(summary.latencyMs()).isZero();
        assertThat(settings.values.get(ProxyRoutingService.ROUTING_TEST_RESULTS_KEY))
                .doesNotContain("ttl expired");
    }

    @Test
    void privateProbeTargetIsRejectedWithoutLeakingTargetDetails() {
        DefaultProxyPathProbe probe = new DefaultProxyPathProbe();

        ProxyPathProbeResult result = probe.probe(
                "localhost", "https://localhost/redirect?token=secret", ProxyEgressPath.DIRECT, 500
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("target_rejected");
        assertThat(result.error()).doesNotContain("localhost", "secret", "redirect");
    }

    @Test
    void redirectStatusIsRejectedByProbePolicy() {
        assertThat(DefaultProxyPathProbe.isSuccessfulHttpStatus(302)).isFalse();
        assertThat(DefaultProxyPathProbe.errorForHttpStatus(302)).isEqualTo("redirect_rejected");
    }

    private ProxyPathProbeResult result(ProxyEgressPath path, boolean success, long totalMs, String error) {
        return new ProxyPathProbeResult(
                path.name(), success, 5, 10, 20, totalMs - 35, totalMs,
                success ? 204 : null, error, 0, 0
        );
    }

    private MutableSettings settings(Map<String, String> values) {
        return new MutableSettings(values);
    }

    private static final class MutableSettings implements SystemSettingService {
        private final Map<String, String> values;

        private MutableSettings(Map<String, String> values) {
            this.values = new LinkedHashMap<>(values);
        }

        @Override
        public Map<String, String> settings() {
            return Map.copyOf(values);
        }

        @Override
        public Map<String, String> updateSettings(Map<String, String> settings) {
            values.putAll(settings);
            return settings();
        }

        @Override
        public com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse uploadCustomerServiceQr(
                org.springframework.web.multipart.MultipartFile file
        ) {
            return null;
        }
    }
}
