package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.AgentOutboundProxySettings;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundProxyPolicyResolverTest {

    @Test
    void modelProxyUrlEnablesAndOverridesDefaultUrl() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                AgentOutboundProxySettings.PROXY_URL_KEY, "http://global:7890",
                AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "false"
        ));
        AgentModelConfig config = config("{\"proxyUrl\":\"http://model:7890\"}");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.proxyUrl()).isEqualTo("http://model:7890");
    }

    @Test
    void disabledModeForcesNoProxy() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                AgentOutboundProxySettings.PROXY_URL_KEY, "http://global:7890",
                AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "true"
        ));
        AgentModelConfig config = config("{\"proxyMode\":\"disabled\",\"proxyUrl\":\"http://model:7890\"}");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.proxyUrl()).isBlank();
    }

    @Test
    void enabledModeFallsBackToGlobalUrl() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                AgentOutboundProxySettings.PROXY_URL_KEY, "http://global:7890",
                AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "false"
        ));
        AgentModelConfig config = config("{\"proxyMode\":\"enabled\"}");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.proxyUrl()).isEqualTo("http://global:7890");
    }

    @Test
    void inheritDoesNotEnableWhenGlobalDefaultIsFalse() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                AgentOutboundProxySettings.PROXY_URL_KEY, "http://global:7890",
                AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "false"
        ));

        ProxyPolicy policy = resolver.resolve(config("{}"));

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.proxyUrl()).isBlank();
        assertThat(policy.noProxyHosts()).contains("localhost", "127.0.0.1", "::1", "backend", "host.docker.internal");
    }

    private OutboundProxyPolicyResolver resolver(Map<String, String> overrides) {
        Map<String, String> settings = new HashMap<>(AgentOutboundProxySettings.defaults());
        settings.putAll(overrides);
        SystemSettingService service = new SystemSettingService() {
            @Override
            public Map<String, String> settings() {
                return settings;
            }

            @Override
            public Map<String, String> updateSettings(Map<String, String> settings) {
                return settings;
            }

            @Override
            public com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse uploadCustomerServiceQr(org.springframework.web.multipart.MultipartFile file) {
                return null;
            }
        };
        return new OutboundProxyPolicyResolver(service, new ObjectMapper());
    }

    private AgentModelConfig config(String extraAuthJson) {
        AgentModelConfig config = new AgentModelConfig();
        config.setExtraAuthJson(extraAuthJson);
        return config;
    }
}
