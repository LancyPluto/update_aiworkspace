package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ImageGenerationParameterResolver;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelCapabilityResponseContractTest {

    private final ModelCapabilitiesCodec codec = new ModelCapabilitiesCodec(new ObjectMapper());

    @Test
    void modelResponsesExposeStoredCapabilitiesWithoutModelNameInference() {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(7L);
        config.setConfigCode("vision-looking-name");
        config.setDisplayName("GPT-4o vision");
        config.setProvider("openai_compatible");
        config.setModelName("gpt-4o");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setCapabilities("[\" text_generation \",\"TEXT_GENERATION\"]");

        assertThat(AgentModelConfigResponse.from(config, codec).capabilities())
                .containsExactly("TEXT_GENERATION");
        assertThat(InternalAgentModelConfigResponse.from(config).capabilities())
                .containsExactly("TEXT_GENERATION");
        assertThat(DiscoveredModelConfigResponse.from(config, codec).capabilities())
                .containsExactly("TEXT_GENERATION");
        assertThat(ModelOptionItemResponse.from(
                config,
                "openai",
                "OpenAI",
                codec.parse(config.getCapabilities()),
                mock(ImageGenerationParameterResolver.class)
        ).capabilities()).containsExactly("TEXT_GENERATION");
    }
}
