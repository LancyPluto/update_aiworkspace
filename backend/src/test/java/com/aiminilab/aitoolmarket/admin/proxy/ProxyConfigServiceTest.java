package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyConfigServiceTest {

    @Mock
    private SystemSettingService systemSettingService;

    @Mock
    private ProxyConnectionProbe connectionProbe;

    @Test
    void subscriptionSourceUsesMihomoEndpointAndMasksSubscriptionOnRead() {
        when(systemSettingService.settings()).thenReturn(Map.of());
        when(systemSettingService.updateSettings(anyMap(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProxyConfigService service = new ProxyConfigService(systemSettingService, connectionProbe);

        ProxyConfigResponse response = service.update(new ProxyConfigRequest(
                true,
                "SUBSCRIPTION",
                "海外线路",
                "https://airport.example.com/api/v1/client/subscribe?token=secret-token",
                360,
                "http://host.docker.internal:7890",
                null,
                null,
                null,
                null,
                null,
                "localhost,backend"
        ), 7L);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(systemSettingService).updateSettings(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        Map<String, String> saved = captor.getValue();
        assertThat(saved).containsEntry("outbound.proxy.sourceType", "SUBSCRIPTION");
        assertThat(saved).containsEntry("outbound.proxy.subscriptionUrl",
                "https://airport.example.com/api/v1/client/subscribe?token=secret-token");
        assertThat(saved).containsEntry("outbound.proxy.url", "");
        assertThat(saved).containsEntry("outbound.proxy.enabledByDefault", "false");
        assertThat(saved).containsEntry("outbound.proxy.routingEnabled", "true");
        assertThat(response.subscriptionConfigured()).isTrue();
        assertThat(response.subscriptionUrlMasked()).isEqualTo("https://airport.example.com/***?token=***");
        assertThat(response.proxyUrlMasked()).isEqualTo("http://mihomo:7890");
    }

    @Test
    void manualSourceBuildsAuthenticatedProxyUrlButDoesNotExposePassword() {
        when(systemSettingService.settings()).thenReturn(Map.of());
        when(systemSettingService.updateSettings(anyMap(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProxyConfigService service = new ProxyConfigService(systemSettingService, connectionProbe);

        ProxyConfigResponse response = service.update(new ProxyConfigRequest(
                true,
                "MANUAL",
                "东京云主机",
                null,
                null,
                null,
                "SOCKS5",
                "8.8.8.8",
                1080,
                "proxy user",
                "p@ss word",
                "localhost,127.0.0.1"
        ), 8L);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(systemSettingService).updateSettings(captor.capture(), org.mockito.ArgumentMatchers.eq(8L));
        assertThat(captor.getValue())
                .containsEntry("outbound.proxy.url", "")
                .containsEntry("outbound.proxy.enabledByDefault", "false")
                .containsEntry("outbound.proxy.manualProtocol", "SOCKS5")
                .containsEntry("outbound.proxy.manualHost", "8.8.8.8")
                .containsEntry("outbound.proxy.manualPort", "1080")
                .containsEntry("outbound.proxy.manualUsername", "proxy user")
                .containsEntry("outbound.proxy.manualPassword", "p@ss word");
        assertThat(response.manualPasswordConfigured()).isTrue();
        assertThat(response.manualUsernameMasked()).isEqualTo("pr***er");
        assertThat(response.proxyUrlMasked()).isEqualTo("http://mihomo:7890");
    }

    @Test
    void omittedSecretsPreserveStoredValues() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("outbound.proxy.subscriptionUrl", "https://airport.example.com/sub?token=existing");
        current.put("outbound.proxy.manualUsername", "existing-user");
        current.put("outbound.proxy.manualPassword", "existing-password");
        when(systemSettingService.settings()).thenReturn(current);
        when(systemSettingService.updateSettings(anyMap(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProxyConfigService service = new ProxyConfigService(systemSettingService, connectionProbe);

        service.update(new ProxyConfigRequest(
                true,
                "SUBSCRIPTION",
                "主线路",
                "",
                120,
                "http://host.docker.internal:7890",
                null,
                null,
                null,
                null,
                "",
                "localhost"
        ), 9L);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(systemSettingService).updateSettings(captor.capture(), org.mockito.ArgumentMatchers.eq(9L));
        assertThat(captor.getValue())
                .containsEntry("outbound.proxy.subscriptionUrl", "https://airport.example.com/sub?token=existing")
                .containsEntry("outbound.proxy.manualUsername", "existing-user")
                .containsEntry("outbound.proxy.manualPassword", "existing-password");
    }

    @Test
    void manualSourceRejectsPrivateOrLoopbackAddresses() {
        when(systemSettingService.settings()).thenReturn(Map.of());
        ProxyConfigService service = new ProxyConfigService(systemSettingService, connectionProbe);
        ProxyConfigRequest request = new ProxyConfigRequest(
                true,
                "MANUAL",
                "无效节点",
                null,
                null,
                null,
                "HTTP",
                "127.0.0.1",
                8080,
                null,
                null,
                "localhost"
        );

        assertThatThrownBy(() -> service.update(request, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("公网 IP");
    }
}
