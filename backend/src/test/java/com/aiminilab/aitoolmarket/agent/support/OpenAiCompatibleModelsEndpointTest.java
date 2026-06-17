package com.aiminilab.aitoolmarket.agent.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModelsEndpointTest {

    @Test
    void resolvesVolcengineArkApiV3BaseUrl() {
        assertThat(OpenAiCompatibleModelsEndpoint.resolve("https://ark.cn-beijing.volces.com/api/v3"))
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3/models");
    }

    @Test
    void resolvesVolcengineArkBareOrigin() {
        assertThat(OpenAiCompatibleModelsEndpoint.resolve("https://ark.cn-beijing.volces.com"))
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3/models");
    }

    @Test
    void resolvesOpenAiV1BaseUrl() {
        assertThat(OpenAiCompatibleModelsEndpoint.resolve("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1/models");
    }

    @Test
    void appendsV1ModelsForBareOrigin() {
        assertThat(OpenAiCompatibleModelsEndpoint.resolve("https://api.openai.com"))
                .isEqualTo("https://api.openai.com/v1/models");
    }

    @Test
    void keepsExistingModelsEndpoint() {
        assertThat(OpenAiCompatibleModelsEndpoint.resolve("https://api.ofox.ai/v1/models"))
                .isEqualTo("https://api.ofox.ai/v1/models");
    }
}
