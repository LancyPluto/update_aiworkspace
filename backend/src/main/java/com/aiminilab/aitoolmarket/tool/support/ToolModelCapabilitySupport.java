package com.aiminilab.aitoolmarket.tool.support;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ToolModelCapabilitySupport {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private static final Set<String> KNOWN_MODEL_CAPABILITIES = Set.of(
            "TEXT_GENERATION",
            "VISION_INPUT",
            "IMAGE_GENERATION",
            "VIDEO_GENERATION",
            "TEXT_TO_SPEECH",
            "SPEECH_TO_TEXT",
            "MUSIC_GENERATION",
            "AUDIO_GENERATION",
            "EMBEDDING",
            "RERANK"
    );

    private ToolModelCapabilitySupport() {
    }

    public static List<String> resolve(AiTool tool, ObjectMapper objectMapper) {
        if (tool == null) {
            return List.of();
        }
        List<String> stored = parse(tool.getRequiredModelCapabilities(), objectMapper);
        return stored.isEmpty() ? defaultsFor(tool) : stored;
    }

    public static List<String> parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return normalizeLegacy(objectMapper.readValue(json, LIST_TYPE));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static String serialize(List<String> capabilities, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(normalize(capabilities));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize required model capabilities", exception);
        }
    }

    public static List<String> normalize(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                continue;
            }
            normalized.add(capability.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    public static List<String> normalizeLegacy(List<String> capabilities) {
        return normalize(normalize(capabilities).stream()
                .map(ToolModelCapabilitySupport::mapLegacyCapability)
                .toList());
    }

    public static List<String> defaultsFor(AiTool tool) {
        if (tool == null) {
            return List.of("TEXT_GENERATION");
        }
        return defaultsFor(tool.getExecutionHandler(), tool.getToolType());
    }

    public static List<String> defaultsFor(String executionHandler, String toolType) {
        if (executionHandler != null && !executionHandler.isBlank()) {
            String capability = mapLegacyCapability(executionHandler.trim().toUpperCase(Locale.ROOT));
            return isKnownCapability(capability) ? List.of(capability) : List.of("TEXT_GENERATION");
        }
        String normalizedType = toolType == null
                ? ""
                : mapLegacyCapability(toolType.trim().toUpperCase(Locale.ROOT));
        return switch (normalizedType) {
            case "IMAGE_TO_IMAGE" -> List.of("IMAGE_GENERATION");
            case "IMAGE_UNDERSTANDING" -> List.of("TEXT_GENERATION", "VISION_INPUT");
            case "AGENT" -> List.of("TEXT_GENERATION");
            default -> isKnownCapability(normalizedType)
                    ? List.of(normalizedType)
                    : List.of("TEXT_GENERATION");
        };
    }

    public static boolean isKnownCapability(String capability) {
        return capability != null && KNOWN_MODEL_CAPABILITIES.contains(capability.trim().toUpperCase(Locale.ROOT));
    }

    private static String mapLegacyCapability(String capability) {
        return "DIGITAL_HUMAN".equals(capability) ? "VIDEO_GENERATION" : capability;
    }
}
