package com.aiminilab.aitoolmarket.agent.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VolcengineEndpointSupportTest {

    @Test
    void normalizesArkRootForOpenAiCompatibleProvider() {
        assertThat(VolcengineEndpointSupport.normalizeProviderBaseUrl(
                "openai_compatible",
                "https://ark.cn-beijing.volces.com"
        )).isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
    }

    @Test
    void normalizesArkRootForSeedanceProvider() {
        assertThat(VolcengineEndpointSupport.normalizeProviderBaseUrl(
                "seedance",
                "https://ark.cn-beijing.volces.com"
        )).isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
    }

    @Test
    void keepsOtherProvidersUnchanged() {
        assertThat(VolcengineEndpointSupport.normalizeProviderBaseUrl(
                "deepseek",
                "https://api.deepseek.com"
        )).isEqualTo("https://api.deepseek.com");
    }
}
