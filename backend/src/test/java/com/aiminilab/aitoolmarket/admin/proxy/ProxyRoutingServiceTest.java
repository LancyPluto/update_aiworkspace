package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyRoutingServiceTest {

    @Mock
    private SystemSettingService systemSettingService;

    @Test
    void updatePersistsNormalizedRulesAndReportsNoProxyConflict() throws Exception {
        when(systemSettingService.settings()).thenReturn(Map.of(
                "outbound.proxy.noProxyHosts", "localhost,.example.com",
                ProxyRoutingService.ROUTING_TEST_RESULTS_KEY,
                "{\"api.example.com\":{\"success\":true,\"latencyMs\":120,\"testedAt\":\"2026-07-16T08:00:00Z\"},"
                        + "\"removed.example.com\":{\"success\":false,\"latencyMs\":0,\"testedAt\":\"2026-07-16T07:00:00Z\"}}"
        ));
        when(systemSettingService.updateSettings(anyMap(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProxyRoutingService service = new ProxyRoutingService(systemSettingService, new ObjectMapper());
        ProxyRoutingUpdateRequest request = new ProxyRoutingUpdateRequest(
                List.of(new ProxyRoutingRule(
                        "ofox-api",
                        "EXACT",
                        "API.Example.com",
                        "PROXY",
                        300,
                        true,
                        "image edit API",
                        ""
                )),
                ProxyAutoSettings.defaults()
        );

        ProxyRoutingResponse response = service.update(request, 42L);

        ArgumentCaptor<Map<String, String>> saved = ArgumentCaptor.forClass(Map.class);
        verify(systemSettingService).updateSettings(saved.capture(), org.mockito.ArgumentMatchers.eq(42L));
        String json = saved.getValue().get(MihomoConfigRenderer.ROUTING_CONFIG_KEY);
        ProxyRoutingConfig persisted = new ObjectMapper().readValue(json, ProxyRoutingConfig.class);
        assertThat(persisted.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.pattern()).isEqualTo("api.example.com");
            assertThat(rule.strategy()).isEqualTo("PROXY");
        });
        assertThat(saved.getValue()).containsEntry("outbound.proxy.routingEnabled", "true");
        Map<String, ProxyDomainTestSummary> retained = new ObjectMapper().readValue(
                saved.getValue().get(ProxyRoutingService.ROUTING_TEST_RESULTS_KEY),
                new TypeReference<>() { }
        );
        assertThat(retained).containsOnlyKeys("api.example.com");
        assertThat(response.fallbackStrategy()).isEqualTo("DIRECT");
        assertThat(response.testResults()).containsOnlyKeys("api.example.com");
        assertThat(response.warnings()).singleElement().asString()
                .contains("NO_PROXY")
                .contains("api.example.com");
    }

    @Test
    void autoRuleRequiresIndependentHttpsProbeUrl() {
        ProxyRoutingService service = new ProxyRoutingService(systemSettingService, new ObjectMapper());
        ProxyRoutingUpdateRequest request = new ProxyRoutingUpdateRequest(
                List.of(new ProxyRoutingRule(
                        "auto-ofox", "EXACT", "api.ofox.ai", "AUTO", 100, true, "", ""
                )),
                ProxyAutoSettings.defaults()
        );

        assertThatThrownBy(() -> service.update(request, 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTPS")
                .hasMessageContaining("probe");
    }

    @Test
    void autoProbeHostMustMatchItsRoutingRule() {
        ProxyRoutingService service = new ProxyRoutingService(systemSettingService, new ObjectMapper());
        ProxyRoutingUpdateRequest request = new ProxyRoutingUpdateRequest(
                List.of(new ProxyRoutingRule(
                        "auto-media", "WILDCARD", "*.media.example.com", "AUTO", 100, true, "",
                        "https://media.example.com/health"
                )),
                ProxyAutoSettings.defaults()
        );

        assertThatThrownBy(() -> service.update(request, 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("probe")
                .hasMessageContaining("命中");
    }

    @Test
    void savingRulesMigratesLegacyGlobalSocksWithoutExposingItsValue() {
        when(systemSettingService.settings()).thenReturn(Map.of(
                "outbound.proxy.url", "socks5://user:password@upstream.example:1080"
        ));
        when(systemSettingService.updateSettings(anyMap(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProxyRoutingService service = new ProxyRoutingService(systemSettingService, new ObjectMapper());

        ProxyRoutingResponse response = service.update(
                new ProxyRoutingUpdateRequest(List.of(), ProxyAutoSettings.defaults()), 42L
        );

        ArgumentCaptor<Map<String, String>> saved = ArgumentCaptor.forClass(Map.class);
        verify(systemSettingService).updateSettings(saved.capture(), org.mockito.ArgumentMatchers.eq(42L));
        assertThat(saved.getValue())
                .containsEntry("outbound.proxy.url", "")
                .containsEntry("outbound.proxy.enabledByDefault", "false")
                .containsEntry("outbound.proxy.routingEnabled", "false");
        assertThat(response.warnings()).singleElement().asString()
                .contains("历史全局 SOCKS")
                .doesNotContain("user", "password", "upstream.example");
    }
}
