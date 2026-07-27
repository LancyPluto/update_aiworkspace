package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptBananaSettingsMapperTest {

    @Test
    void mapsOpenAiCompatibleTextConfig() {
        AgentModelConfig text = new AgentModelConfig();
        text.setProvider("openai_compatible");
        text.setModelName("deepseek-chat");
        text.setBaseUrl("https://api.deepseek.com");
        text.setApiKey("sk-test");

        Map<String, Object> body = PptBananaSettingsMapper.toBananaSettings(text, null);

        assertEquals("openai", body.get("ai_provider_format"));
        assertEquals("deepseek-chat", body.get("text_model"));
        assertEquals("openai", body.get("text_model_source"));
        assertEquals("sk-test", body.get("text_api_key"));
        assertEquals("https://api.deepseek.com", body.get("text_api_base_url"));
    }

    @Test
    void mapsSiliconflowImageToLazyllm() {
        AgentModelConfig image = new AgentModelConfig();
        image.setProvider("siliconflow_images");
        image.setModelName("Tongyi-MAI/Z-Image-Turbo");
        image.setApiKey("sf-key");

        Map<String, Object> body = PptBananaSettingsMapper.toBananaSettings(null, image);

        assertEquals("lazyllm", body.get("ai_provider_format"));
        assertEquals("siliconflow", body.get("image_model_source"));
        assertEquals("Tongyi-MAI/Z-Image-Turbo", body.get("image_model"));
        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) body.get("lazyllm_api_keys");
        assertEquals("sf-key", keys.get("siliconflow"));
    }

    @Test
    void mapsPlatformGptImage2GatewayWithoutASecondPptCredential() {
        AgentModelConfig image = new AgentModelConfig();
        image.setProvider("ofox_openai_images");
        image.setModelName("openai/gpt-image-2");
        image.setBaseUrl("https://api.ofox.ai/v1");
        image.setApiKey("platform-account-key");

        Map<String, Object> body = PptBananaSettingsMapper.toBananaSettings(null, image);

        assertEquals("openai", body.get("ai_provider_format"));
        assertEquals("openai", body.get("image_model_source"));
        assertEquals("openai/gpt-image-2", body.get("image_model"));
        assertEquals("platform-account-key", body.get("image_api_key"));
        assertEquals("https://api.ofox.ai/v1", body.get("image_api_base_url"));
    }

    @Test
    void resolveBananaSourceHandlesAliases() {
        assertEquals("doubao", PptBananaSettingsMapper.resolveBananaSource("seedance"));
        assertEquals("gemini", PptBananaSettingsMapper.resolveBananaSource("google_gemini"));
        assertTrue(PptBananaSettingsMapper.resolveBananaSource("openai_compatible").equals("openai"));
    }
}
