package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VendorCodeResolverTest {

    private VendorCodeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VendorCodeResolver(new ModelProviderRegistry(), mock(ModelVendorMapper.class));
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
        )).isEqualTo("openai_gateway");
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
}
