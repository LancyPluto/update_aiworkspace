package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.dto.ImageGenerationParametersResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageGenerationParameterResolverTest {

    private final ImageGenerationParameterResolver resolver = new ImageGenerationParameterResolver(new ObjectMapper());

    @Test
    void defaultsToSingleImageWhenRequestSchemaDoesNotDeclareAnOutputCount() {
        AgentModelConfig config = imageModel();
        config.setExtraAuthJson("{\"counts\":[1,2,3,4],\"maxImagesPerRequest\":4}");
        config.setRequestSchemaJson("{\"version\":\"1\",\"fields\":[{\"key\":\"prompt\",\"type\":\"string\"}]}");

        ImageGenerationParametersResponse parameters = resolver.resolve(config);

        assertThat(parameters.counts()).containsExactly(1);
        assertThat(parameters.defaultCount()).isEqualTo(1);
    }

    @Test
    void exposesMultipleImagesOnlyFromAnExplicitRequestSchemaCountField() {
        AgentModelConfig config = imageModel();
        config.setRequestSchemaJson("""
                {"version":"1","fields":[
                  {"key":"batch_size","type":"integer","default":2,"min":1,"max":4}
                ]}
                """);

        ImageGenerationParametersResponse parameters = resolver.resolve(config);

        assertThat(parameters.counts()).containsExactly(1, 2, 3, 4);
        assertThat(parameters.defaultCount()).isEqualTo(2);
    }

    private static AgentModelConfig imageModel() {
        AgentModelConfig config = new AgentModelConfig();
        config.setProvider("agnes_images");
        config.setModelName("agnes-image-2.1-flash");
        config.setCapabilities("[\"IMAGE_GENERATION\"]");
        return config;
    }
}
