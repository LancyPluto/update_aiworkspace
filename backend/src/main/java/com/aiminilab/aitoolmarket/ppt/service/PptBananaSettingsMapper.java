package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 将超市 {@link AgentModelConfig} 映射为 banana-slides {@code PUT /api/settings} 请求体。
 */
public final class PptBananaSettingsMapper {

    private static final Set<String> LAZYLLM_VENDORS = Set.of(
            "qwen", "doubao", "deepseek", "glm", "siliconflow", "sensenova", "minimax", "kimi"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PptBananaSettingsMapper() {
    }

    public static Map<String, Object> toBananaSettings(AgentModelConfig text, AgentModelConfig image) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, String> lazyllmKeys = new LinkedHashMap<>();

        if (text != null) {
            applyModel(body, lazyllmKeys, "text", text);
        }
        if (image != null) {
            applyModel(body, lazyllmKeys, "image", image);
        }

        if (!lazyllmKeys.isEmpty()) {
            body.put("ai_provider_format", "lazyllm");
            body.put("lazyllm_api_keys", lazyllmKeys);
        } else if (!body.containsKey("ai_provider_format") && text != null) {
            body.put("ai_provider_format", resolveBananaSource(text.getProvider()));
        } else if (!body.containsKey("ai_provider_format") && image != null) {
            body.put("ai_provider_format", resolveBananaSource(image.getProvider()));
        }

        return body;
    }

    private static void applyModel(Map<String, Object> body,
                                   Map<String, String> lazyllmKeys,
                                   String role,
                                   AgentModelConfig config) {
        String source = resolveBananaSource(config.getProvider());
        String modelName = blankToNull(config.getModelName());
        String apiKey = blankToNull(config.getApiKey());
        String baseUrl = blankToNull(config.getBaseUrl());

        if (modelName != null) {
            body.put(role + "_model", modelName);
        }

        if (LAZYLLM_VENDORS.contains(source)) {
            body.put(role + "_model_source", source);
            if (apiKey != null) {
                lazyllmKeys.put(source, apiKey);
            }
            mergeLazyllmKeysFromExtraAuth(lazyllmKeys, config.getExtraAuthJson(), source);
            return;
        }

        body.put(role + "_model_source", source);
        if (apiKey != null) {
            body.put(role + "_api_key", apiKey);
        }
        if (baseUrl != null) {
            body.put(role + "_api_base_url", baseUrl);
        }

        if ("openai".equals(source) || "gemini".equals(source) || "anthropic".equals(source)) {
            if (!body.containsKey("ai_provider_format")) {
                body.put("ai_provider_format", source);
            }
            if (apiKey != null && !body.containsKey("api_key")) {
                body.put("api_key", apiKey);
            }
            if (baseUrl != null && !body.containsKey("api_base_url")) {
                body.put("api_base_url", baseUrl);
            }
        }
    }

    private static void mergeLazyllmKeysFromExtraAuth(Map<String, String> lazyllmKeys,
                                                      String extraAuthJson,
                                                      String primaryVendor) {
        if (extraAuthJson == null || extraAuthJson.isBlank()) {
            return;
        }
        try {
            Map<String, Object> extra = OBJECT_MAPPER.readValue(extraAuthJson, new TypeReference<>() {});
            Object vendorKey = extra.get(primaryVendor + "ApiKey");
            if (vendorKey == null) {
                vendorKey = extra.get("apiKey");
            }
            if (vendorKey != null && !vendorKey.toString().isBlank()) {
                lazyllmKeys.putIfAbsent(primaryVendor, vendorKey.toString().trim());
            }
            Object keys = extra.get("lazyllmApiKeys");
            if (keys instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    if (k != null && v != null && !v.toString().isBlank()) {
                        lazyllmKeys.put(k.toString(), v.toString().trim());
                    }
                });
            }
        } catch (Exception ignored) {
            // ignore malformed extra auth
        }
    }

    static String resolveBananaSource(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return "openai";
        }
        String normalized = providerCode.trim().toLowerCase();
        return switch (normalized) {
            case "openai_compatible", "openai" -> "openai";
            case "anthropic_compatible", "anthropic" -> "anthropic";
            case "google_gemini", "gemini" -> "gemini";
            case "siliconflow_images", "siliconflow_speech", "siliconflow" -> "siliconflow";
            case "minimax", "minimax_speech", "minimax_music" -> "minimax";
            case "deepseek", "deepseek_compatible" -> "deepseek";
            case "doubao", "volcengine", "seedance", "volcengine_images" -> "doubao";
            case "qwen", "qwen_compatible" -> "qwen";
            case "glm", "zhipu", "glm_compatible" -> "glm";
            case "kimi", "moonshot", "moonshot_compatible" -> "kimi";
            case "sensenova" -> "sensenova";
            case "kling_video" -> "kling";
            default -> {
                if (normalized.contains("gemini")) {
                    yield "gemini";
                }
                if (normalized.contains("anthropic")) {
                    yield "anthropic";
                }
                if (normalized.contains("siliconflow")) {
                    yield "siliconflow";
                }
                if (normalized.contains("deepseek")) {
                    yield "deepseek";
                }
                if (normalized.contains("doubao") || normalized.contains("volc")) {
                    yield "doubao";
                }
                if (normalized.contains("qwen")) {
                    yield "qwen";
                }
                yield "openai";
            }
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
