package com.aiminilab.aitoolmarket.agent.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEndpointSupportTest {

    @Test
    void keepsBareOriginWithoutEndpointPath() {
        var normalized = OpenAiCompatibleEndpointSupport.normalize("https://www.apiporter.com");

        assertThat(normalized.baseUrl()).isEqualTo("https://www.apiporter.com");
        assertThat(normalized.endpointPath()).isNull();
    }

    @Test
    void keepsVersionedRootWithoutEndpointPath() {
        var normalized = OpenAiCompatibleEndpointSupport.normalize("https://www.apiporter.com/v1");

        assertThat(normalized.baseUrl()).isEqualTo("https://www.apiporter.com/v1");
        assertThat(normalized.endpointPath()).isNull();
    }

    @Test
    void splitsChatCompletionsEndpoint() {
        var normalized = OpenAiCompatibleEndpointSupport.normalize("https://www.apiporter.com/v1/chat/completions");

        assertThat(normalized.baseUrl()).isEqualTo("https://www.apiporter.com/v1");
        assertThat(normalized.endpointPath()).isEqualTo("/chat/completions");
    }

    @Test
    void splitsImagesGenerationsEndpoint() {
        var normalized = OpenAiCompatibleEndpointSupport.normalize("https://www.apiporter.com/v1/images/generations/");

        assertThat(normalized.baseUrl()).isEqualTo("https://www.apiporter.com/v1");
        assertThat(normalized.endpointPath()).isEqualTo("/images/generations");
    }
}
