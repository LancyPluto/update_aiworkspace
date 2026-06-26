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
        assertThat(registry.isSupported("local_media_mock")).isTrue();
        assertThat(registry.isSupported("siliconflow_images")).isTrue();
        Optional<ModelProviderDefinition> siliconflow = registry.findByCode("siliconflow_images");
        assertThat(siliconflow).isPresent();
        assertThat(siliconflow.get().capabilities()).contains("IMAGE_GENERATION", "DIGITAL_HUMAN");
        Optional<ModelProviderDefinition> localMediaMock = registry.findByCode("local_media_mock");
        assertThat(localMediaMock).isPresent();
        assertThat(localMediaMock.get().capabilities()).containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
        assertThat(localMediaMock.get().testStrategy()).isEqualTo("accept_only");
        assertThat(registry.listByCapability("TEXT_GENERATION")).isNotEmpty();
        Optional<ModelProviderDefinition> qwen = registry.findByCode("qwen");
        assertThat(qwen).isPresent();
        assertThat(qwen.get().defaultModel()).isEqualTo("qwen3.6-plus");
        assertThat(qwen.get().defaultBaseUrl()).isEqualTo("https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        assertThat(qwen.get().providerProtocol()).isEqualTo("openai_chat");
        assertThat(qwen.get().capabilities()).containsExactly("TEXT_GENERATION", "VISION_INPUT");
    }

    @Test
    void loadsAgnesProvidersForChatImagesAndVideo() {
        Optional<ModelProviderDefinition> chat = registry.findByCode("agnes_chat");
        Optional<ModelProviderDefinition> images = registry.findByCode("agnes_images");
        Optional<ModelProviderDefinition> video = registry.findByCode("agnes_video");

        assertThat(chat).isPresent();
        assertThat(chat.get().defaultBaseUrl()).isEqualTo("https://apihub.agnes-ai.com/v1");
        assertThat(chat.get().defaultModel()).isEqualTo("agnes-2.0-flash");
        assertThat(chat.get().capabilities()).containsExactly("TEXT_GENERATION");

        assertThat(images).isPresent();
        assertThat(images.get().defaultBaseUrl()).isEqualTo("https://apihub.agnes-ai.com/v1");
        assertThat(images.get().defaultModel()).isEqualTo("agnes-image-2.1-flash");
        assertThat(images.get().providerProtocol()).isEqualTo("openai_images");
        assertThat(images.get().capabilities()).containsExactly("IMAGE_GENERATION");

        assertThat(video).isPresent();
        assertThat(video.get().defaultBaseUrl()).isEqualTo("https://apihub.agnes-ai.com");
        assertThat(video.get().defaultModel()).isEqualTo("agnes-video-v2.0");
        assertThat(video.get().providerProtocol()).isEqualTo("agnes_video");
        assertThat(video.get().capabilities()).containsExactly("VIDEO_GENERATION");
    }
}
