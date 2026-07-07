package com.aiminilab.aitoolmarket.agent.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AgentVisionInputSupport {

    private AgentVisionInputSupport() {
    }

    public static List<String> withInferredVisionInput(String provider,
                                                       String modelName,
                                                       String baseUrl,
                                                       List<String> capabilities) {
        List<String> normalized = normalizeCapabilities(capabilities);
        if (!hasCapability(normalized, "TEXT_GENERATION")
                || hasCapability(normalized, "VISION_INPUT")
                || !isKnownVisionInputModel(provider, modelName, baseUrl)) {
            return normalized;
        }
        List<String> enriched = new ArrayList<>(normalized);
        enriched.add("VISION_INPUT");
        return List.copyOf(enriched);
    }

    public static boolean isKnownVisionInputModel(String provider, String modelName, String baseUrl) {
        String normalizedProvider = normalize(provider);
        String normalizedModel = normalize(modelName);
        String normalizedBaseUrl = normalize(baseUrl);
        if (isKnownQwenVisionModel(normalizedProvider, normalizedModel, normalizedBaseUrl)) {
            return true;
        }
        return isVolcengineArk(normalizedProvider, normalizedBaseUrl)
                && normalizedModel.contains("doubao-seed-2")
                && !normalizedModel.contains("code");
    }

    private static boolean isKnownQwenVisionModel(String provider, String modelName, String baseUrl) {
        boolean qwenProvider = provider.equals("qwen")
                || provider.equals("qwen_compatible")
                || provider.equals("dashscope")
                || provider.equals("bailian")
                || baseUrl.contains("dashscope.aliyuncs.com")
                || baseUrl.contains("maas.aliyuncs.com");
        if (!qwenProvider) {
            return false;
        }
        return modelName.contains("qwen3.6-plus")
                || modelName.contains("qwen-vl")
                || modelName.contains("qwen2-vl")
                || modelName.contains("qwen2.5-vl")
                || modelName.contains("qwen3-vl")
                || modelName.contains("qvq")
                || modelName.contains("omni");
    }

    private static boolean isVolcengineArk(String provider, String baseUrl) {
        return provider.equals("volcengine")
                || provider.equals("openai_compatible")
                || baseUrl.contains("volces.com")
                || baseUrl.contains("volcengine.com");
    }

    private static List<String> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        return capabilities.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static boolean hasCapability(List<String> capabilities, String expected) {
        return capabilities.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
