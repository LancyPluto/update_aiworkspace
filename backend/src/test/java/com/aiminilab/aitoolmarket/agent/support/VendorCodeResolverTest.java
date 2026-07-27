package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VendorCodeResolverTest {

    private VendorCodeResolver resolver;

    @BeforeEach
    void setUp() {
        ModelProviderRegistry providerRegistry = new ModelProviderRegistry();
        ReflectionTestUtils.invokeMethod(providerRegistry, "load");
        resolver = new VendorCodeResolver(providerRegistry, mock(ModelVendorMapper.class));
    }

    @Test
    void openAiCompatibleModelsAreGroupedByActualVendor() {
        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "Gemini 2.5 Pro",
                "gemini-2.5-pro"
        )).isEqualTo("google");

        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "通义千问 Plus",
                "qwen-plus"
        )).isEqualTo("qwen");

        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://open.bigmodel.cn/api/paas/v4",
                "智谱 GLM-4",
                "glm-4"
        )).isEqualTo("zhipu");

        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://api.moonshot.cn/v1",
                "Kimi K2.6",
                "kimi-k2.6"
        )).isEqualTo("moonshot");

        assertThat(resolver.resolveVendorCode(
                "moonshot_compatible",
                null,
                "Kimi K3",
                "kimi-k3"
        )).isEqualTo("moonshot");

        assertThat(resolver.resolveVendorCode(
                "anthropic_compatible",
                "https://api.anthropic.com",
                "Claude Sonnet 4.6",
                "claude-sonnet-4-6"
        )).isEqualTo("anthropic");
    }

    @Test
    void officialOpenAiCompatibleModelsStayInOpenAiGroup() {
        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://api.openai.com/v1",
                "OpenAI GPT-5.5",
                "gpt-5.5"
        )).isEqualTo("openai");

        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "",
                "OpenAI GPT-5.5",
                "gpt-5.5"
        )).isEqualTo("openai");

        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://api.ofox.ai/v1",
                "GPT-Image-2",
                "openai/gpt-image-2"
        )).isEqualTo("ofox");
    }

    @Test
    void imageGatewayUsesStrictOperatorHostAndPublicUpstreamVendor() {
        assertThat(resolver.resolveVendorCode(
                "ofox_openai_images",
                "https://relay.example/v1",
                "oFox images",
                "openai/gpt-image-2"
        )).isEqualTo("ofox");
        assertThat(resolver.resolveVendorCode(
                "openai_images_gateway",
                "https://api.ofox.ai/v1",
                "Gateway images",
                "openai/gpt-image-2"
        )).isEqualTo("ofox");
        assertThat(resolver.resolveVendorCode(
                "openai_images_gateway",
                "https://api.openai.com/v1",
                "Official images",
                "gpt-image-1"
        )).isEqualTo("openai");
        assertThat(resolver.resolveVendorCode(
                "openai_images_gateway",
                "https://ofox.ai.evil.example/v1",
                "Unknown gateway",
                "gpt-image-1"
        )).isEqualTo("other");
        assertThat(resolver.resolveVendorCode(
                "openai_images_gateway",
                "https://api.openai.com.evil.example/v1",
                "Unknown gateway",
                "gpt-image-1"
        )).isEqualTo("other");

        com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount declaredOpenAi =
                new com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount();
        declaredOpenAi.setVendorCode("openai");
        declaredOpenAi.setBaseUrl("https://ofox.ai.evil.example/v1");
        assertThat(resolver.resolveEffectiveVendorCode(declaredOpenAi)).isEqualTo("openai");
        declaredOpenAi.setBaseUrl("https://api.moonshot.cn/v1");
        assertThat(resolver.resolveEffectiveVendorCode(declaredOpenAi)).isEqualTo("moonshot");

        com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig config =
                new com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig();
        config.setProvider("ofox_openai_images");
        config.setBaseUrl("https://api.ofox.ai/v1");
        assertThat(resolver.resolvePublicVendorCode(config)).isEqualTo("openai");
        assertThat(resolver.usesBoundAccountVendor("openai_images_gateway")).isTrue();
        assertThat(resolver.usesBoundAccountVendor("ofox_openai_images")).isFalse();
    }

    @Test
    void sunoMusicModelsAreGroupedBySunoVendor() {
        assertThat(resolver.resolveVendorCode(
                "suno_music",
                "https://api.sunoapi.org",
                "SunoV5_5",
                "V5_5"
        )).isEqualTo("suno");

        assertThat(resolver.resolveVendorCode(
                "openai_compatible",
                "https://api.sunoapi.org",
                "Suno account",
                "V5"
        )).isEqualTo("suno");

        assertThat(resolver.vendorIconAsset("suno")).isEqualTo("suno");
    }

    @Test
    void happyHorseUsesQwenVendorAccountGroup() {
        assertThat(resolver.resolveVendorCode(
                "bailian_happyhorse",
                "https://dashscope.aliyuncs.com",
                "HappyHorse 文生视频",
                "happyhorse-1.0-t2v"
        )).isEqualTo("qwen");

        assertThat(resolver.vendorLabel("qwen")).isEqualTo("阿里云百炼");
    }

    @Test
    void dashscopeQwenTtsUsesQwenVendorAccountGroup() {
        assertThat(resolver.resolveVendorCode(
                "dashscope_qwen_tts",
                "https://dashscope.aliyuncs.com",
                "Qwen3 TTS",
                "qwen3-tts-flash"
        )).isEqualTo("qwen");
    }

    @Test
    void aliyunVendorAliasesCanonicalizeToQwen() {
        assertThat(resolver.canonicalVendorCode("bailian_happyhorse")).isEqualTo("qwen");
        assertThat(resolver.canonicalVendorCode("dashscope")).isEqualTo("qwen");
        assertThat(resolver.canonicalVendorCode("aliyun_bailian")).isEqualTo("qwen");
    }

    @Test
    void accountVendorCanonicalizationRejectsVirtualGatewayAndGroupsSuno() {
        assertThat(resolver.canonicalVendorCode("openai_gateway")).isEqualTo("openai_gateway");
        assertThatThrownBy(() -> resolver.requireConcreteVendorCode("openai_gateway"))
                .hasMessageContaining("actual credential issuer");
        assertThat(resolver.canonicalVendorCode("suno_music")).isEqualTo("suno");
    }

    @Test
    void agnesProvidersAreGroupedAsAgnesVendor() {
        assertThat(resolver.resolveVendorCode("agnes_chat", "https://apihub.agnes-ai.com/v1", "Agnes 2.0", "agnes-2.0-flash"))
                .isEqualTo("agnes");
        assertThat(resolver.resolveVendorCode("agnes_images", "https://apihub.agnes-ai.com/v1", "Agnes Image", "agnes-image-2.1-flash"))
                .isEqualTo("agnes");
        assertThat(resolver.resolveVendorCode("agnes_video", "https://apihub.agnes-ai.com", "Agnes Video", "agnes-video-v2.0"))
                .isEqualTo("agnes");
        assertThat(resolver.vendorLabel("agnes")).isEqualTo("Agnes AI");
        assertThat(resolver.vendorIconAsset("agnes")).isEqualTo("agnes");
    }
}
