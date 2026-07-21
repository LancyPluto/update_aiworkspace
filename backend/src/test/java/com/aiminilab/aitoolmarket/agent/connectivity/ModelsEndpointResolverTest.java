package com.aiminilab.aitoolmarket.agent.connectivity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelsEndpointResolverTest {

    private final ModelsEndpointResolver resolver = new ModelsEndpointResolver();

    @Test
    void resolvesAgnesModelsWithoutDuplicatingVersionPath() {
        assertThat(resolver.resolve("agnes", "https://apihub.agnes-ai.com"))
                .contains("https://apihub.agnes-ai.com/v1/models");
        assertThat(resolver.resolve("agnes", "https://apihub.agnes-ai.com/v1"))
                .contains("https://apihub.agnes-ai.com/v1/models");
        assertThat(resolver.resolve("agnes", "https://apihub.agnes-ai.com/v1/models"))
                .contains("https://apihub.agnes-ai.com/v1/models");
    }

    @Test
    void leavesOtherVendorsAndInvalidUrlsToTheirExistingStrategies() {
        assertThat(resolver.resolve("openai", "https://api.openai.com/v1")).isEmpty();
        assertThat(resolver.resolve("minimax", "https://api.minimax.chat/v1")).isEmpty();
        assertThat(resolver.resolve("agnes", "not-a-valid-url")).isEmpty();
    }
}
