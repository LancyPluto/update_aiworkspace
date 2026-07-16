package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingRule;
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
    void legacyModelAndGlobalSocksUrlsNeverReachWorkerPolicy() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                AgentOutboundProxySettings.PROXY_URL_KEY, "socks5://global-upstream.example:1080",
                AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "true",
                "outbound.proxy.routing", "{\"rules\":[{\"id\":\"ofox\",\"patternType\":\"EXACT\",\"pattern\":\"api.ofox.ai\",\"strategy\":\"PROXY\",\"priority\":100,\"enabled\":true,\"note\":\"\",\"probeUrl\":\"\"}]}"
        ));
        AgentModelConfig config = config("{\"proxyMode\":\"inherit\",\"proxyUrl\":\"socks5://model-upstream.example:1080\"}");
        config.setBaseUrl("https://api.ofox.ai/v1");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.mode()).isEqualTo("PROXY");
        assertThat(policy.proxyUrl()).isEqualTo("http://mihomo:7890");
        assertThat(policy.proxyUrl()).doesNotContain("socks5", "upstream");
        assertThat(policy.routingRules()).singleElement().satisfies(rule -> {
            assertThat(rule.pattern()).isEqualTo("api.ofox.ai");
            assertThat(rule.strategy()).isEqualTo("PROXY");
        });
        assertThat(policy.businessFallback()).isEqualTo("DIRECT");
    }

    @Test
    void historicalDisabledModeCannotOverrideCurrentDomainRule() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                "outbound.proxy.routing", "{\"rules\":[{\"id\":\"ofox\",\"patternType\":\"EXACT\",\"pattern\":\"api.ofox.ai\",\"strategy\":\"PROXY\",\"priority\":100,\"enabled\":true,\"note\":\"\",\"probeUrl\":\"\"}]}"
        ));
        AgentModelConfig config = config("{\"proxyMode\":\"disabled\",\"proxyUrl\":\"http://model:7890\"}");
        config.setBaseUrl("https://api.ofox.ai/v1");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.proxyUrl()).isEqualTo("http://mihomo:7890");
    }

    @Test
    void enabledModeWithoutExplicitDomainRulesRemainsDirect() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                AgentOutboundProxySettings.PROXY_URL_KEY, "socks5://global-upstream.example:1080",
                AgentOutboundProxySettings.ENABLED_BY_DEFAULT_KEY, "true"
        ));
        AgentModelConfig config = config("{\"proxyMode\":\"enabled\"}");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.proxyUrl()).isBlank();
    }

    @Test
    void unmatchedBaseUrlStartsDirectButCarriesProjectRulesForPerRequestEvaluation() {
        OutboundProxyPolicyResolver resolver = resolver(Map.of(
                "outbound.proxy.routing", "{\"rules\":[{\"id\":\"uploads\",\"patternType\":\"SUFFIX\",\"pattern\":\"upload.example.com\",\"strategy\":\"AUTO\",\"priority\":100,\"enabled\":true,\"note\":\"\",\"probeUrl\":\"https://health.example.com/ping\"}]}"
        ));
        AgentModelConfig config = config("{}");
        config.setBaseUrl("https://api.direct.example/v1");

        ProxyPolicy policy = resolver.resolve(config);

        assertThat(policy.mode()).isEqualTo("DIRECT");
        assertThat(policy.enabled()).isFalse();
        assertThat(policy.proxyUrl()).isBlank();
        assertThat(policy.projectProxyUrl()).isEqualTo("http://mihomo:7890");
        assertThat(policy.routingEnabled()).isTrue();
        assertThat(policy.routingRules()).singleElement()
                .extracting(ProxyRoutingRule::pattern)
                .isEqualTo("upload.example.com");
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
