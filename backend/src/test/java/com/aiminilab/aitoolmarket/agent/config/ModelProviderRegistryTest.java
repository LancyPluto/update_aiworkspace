package com.aiminilab.aitoolmarket.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ModelProviderRegistryTest {

    @Autowired
    private ModelProviderRegistry registry;

    @Test
    void loadsYamlAndSupportsKnownProviders() {
        assertThat(registry.isSupported("openai_compatible")).isTrue();
        assertThat(registry.isSupported("siliconflow_images")).isTrue();
        Optional<ModelProviderDefinition> siliconflow = registry.findByCode("siliconflow_images");
        assertThat(siliconflow).isPresent();
        assertThat(siliconflow.get().capabilities()).contains("IMAGE_GENERATION", "DIGITAL_HUMAN");
        assertThat(registry.listByCapability("TEXT_GENERATION")).isNotEmpty();
    }
}
