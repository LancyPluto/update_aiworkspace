package com.aiminilab.aitoolmarket.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ToolFrontendStyleConfig(
        String primaryColor,
        String welcomeMessage,
        String mediaDisplayMode,
        String modelIconUrl,
        String comparisonOriginalUrl,
        String comparisonEffectUrl,
        String heroTitle,
        String heroSubtitle,
        List<String> demoThumbnails,
        List<String> useCases,
        List<String> steps,
        List<String> recommendedToolCodes,
        String beforeVideoUrl,
        String afterVideoUrl
) {
    private static final Pattern FRONTEND_STYLE_PATTERN = Pattern.compile("<!--\\s*ai-tool-ui:([\\s\\S]*?)\\s*-->");

    public static ToolFrontendStyleConfig defaults() {
        return new ToolFrontendStyleConfig(
                "#3b82f6",
                "",
                "icon",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                ""
        );
    }

    public static ToolFrontendStyleConfig fromConfigNote(String configNote, ObjectMapper objectMapper) {
        if (configNote == null || configNote.isBlank()) {
            return defaults();
        }
        Matcher matcher = FRONTEND_STYLE_PATTERN.matcher(configNote);
        if (!matcher.find()) {
            return defaults();
        }
        try {
            JsonNode node = objectMapper.readTree(matcher.group(1));
            ToolFrontendStyleConfig fallback = defaults();
            return new ToolFrontendStyleConfig(
                    text(node, "primaryColor", fallback.primaryColor()),
                    text(node, "welcomeMessage", ""),
                    displayMode(node.path("mediaDisplayMode").asText("icon")),
                    text(node, "modelIconUrl", ""),
                    text(node, "comparisonOriginalUrl", ""),
                    text(node, "comparisonEffectUrl", ""),
                    text(node, "heroTitle", ""),
                    text(node, "heroSubtitle", ""),
                    stringList(node.get("demoThumbnails")),
                    stringList(node.get("useCases")),
                    stringList(node.get("steps")),
                    stringList(node.get("recommendedToolCodes")),
                    text(node, "beforeVideoUrl", ""),
                    text(node, "afterVideoUrl", "")
            );
        } catch (Exception ignored) {
            return defaults();
        }
    }

    public boolean hasComparisonImages() {
        return !comparisonOriginalUrl.isBlank() && !comparisonEffectUrl.isBlank();
    }

    private static String displayMode(String value) {
        return switch (value == null ? "" : value.trim()) {
            case "comparison", "effect" -> value.trim();
            default -> "icon";
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            return fallback;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? fallback : text;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText().trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
