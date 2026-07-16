package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyRoutingDiagnosticsServiceTest {

    @Test
    void autoPrefersAvailablePathThenLatencyWithoutSendingBusinessRequest() {
        SystemSettingService settings = settings(Map.of(
                MihomoConfigRenderer.ROUTING_CONFIG_KEY,
                "{\"rules\":[{\"id\":\"ofox\",\"patternType\":\"EXACT\",\"pattern\":\"api.ofox.ai\",\"strategy\":\"AUTO\",\"priority\":100,\"enabled\":true,\"note\":\"\",\"probeUrl\":\"https://api.ofox.ai/health\"}],\"autoSettings\":{\"timeoutMs\":5000,\"sampleSize\":6,\"switchThresholdMs\":150,\"hysteresisMs\":80,\"cooldownSeconds\":300}}"
        ));
        ProxyPathProbe probe = (domain, probeUrl, path, timeoutMs) -> path == ProxyEgressPath.DIRECT
                ? result(path, false, 90, "network unreachable")
                : result(path, true, 220, "");
        ProxyRoutingDiagnosticsService service = new ProxyRoutingDiagnosticsService(
                settings, new ObjectMapper(), probe
        );

        ProxyDomainTestResponse response = service.test(new ProxyDomainTestRequest("api.ofox.ai", ""));

        assertThat(response.domain()).isEqualTo("api.ofox.ai");
        assertThat(response.matchedRuleId()).isEqualTo("ofox");
        assertThat(response.direct().success()).isFalse();
        assertThat(response.proxy().success()).isTrue();
        assertThat(response.autoDecision().selectedPath()).isEqualTo("PROXY");
        assertThat(response.autoDecision().reason()).contains("可用性");
        assertThat(response.probeMethod()).isEqualTo("HEAD");
    }

    @Test
    void probeUrlMustTargetTheSameDomainAsStageMeasurements() {
        ProxyRoutingDiagnosticsService service = new ProxyRoutingDiagnosticsService(
                settings(Map.of()), new ObjectMapper(),
                (domain, probeUrl, path, timeoutMs) -> result(path, true, 20, "")
        );

        assertThatThrownBy(() -> service.test(new ProxyDomainTestRequest(
                "api.ofox.ai", "https://health.example.com/ping"
        ))).isInstanceOf(com.aiminilab.aitoolmarket.common.exception.BusinessException.class)
                .hasMessageContaining("同一域名");
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

    private SystemSettingService settings(Map<String, String> values) {
        return new SystemSettingService() {
            @Override
            public Map<String, String> settings() {
                return values;
            }

            @Override
            public Map<String, String> updateSettings(Map<String, String> settings) {
                return settings;
            }

            @Override
            public com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse uploadCustomerServiceQr(
                    org.springframework.web.multipart.MultipartFile file
            ) {
                return null;
            }
        };
    }
}
