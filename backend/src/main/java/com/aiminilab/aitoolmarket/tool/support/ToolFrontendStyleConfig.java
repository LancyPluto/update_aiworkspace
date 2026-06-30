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
        String audioPreviewUrl,
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
                    text(node, "audioPreviewUrl", ""),
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

    /** 导入配置包时：incoming 优先，空 URL 字段回退到 existing。 */
    public ToolFrontendStyleConfig mergePreservingMediaUrls(ToolFrontendStyleConfig existing) {
        if (existing == null) {
            return this;
        }
        return new ToolFrontendStyleConfig(
                primaryColor,
                welcomeMessage,
                mediaDisplayMode,
                preferNonBlank(modelIconUrl, existing.modelIconUrl),
                preferNonBlank(comparisonOriginalUrl, existing.comparisonOriginalUrl),
                preferNonBlank(comparisonEffectUrl, existing.comparisonEffectUrl),
                preferNonBlank(audioPreviewUrl, existing.audioPreviewUrl),
                heroTitle,
                heroSubtitle,
                preferNonEmptyList(demoThumbnails, existing.demoThumbnails),
                useCases,
                steps,
                recommendedToolCodes,
                preferNonBlank(beforeVideoUrl, existing.beforeVideoUrl),
                preferNonBlank(afterVideoUrl, existing.afterVideoUrl)
        );
    }

    public String toConfigNoteMarker(ObjectMapper objectMapper) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("primaryColor", primaryColor == null || primaryColor.isBlank() ? "#3b82f6" : primaryColor);
            root.put("welcomeMessage", welcomeMessage == null ? "" : welcomeMessage);
            root.put("mediaDisplayMode", mediaDisplayMode == null || mediaDisplayMode.isBlank() ? "icon" : mediaDisplayMode);
            root.put("modelIconUrl", modelIconUrl == null ? "" : modelIconUrl);
            root.put("comparisonOriginalUrl", comparisonOriginalUrl == null ? "" : comparisonOriginalUrl);
            root.put("comparisonEffectUrl", comparisonEffectUrl == null ? "" : comparisonEffectUrl);
            root.put("audioPreviewUrl", audioPreviewUrl == null ? "" : audioPreviewUrl);
            root.put("heroTitle", heroTitle == null ? "" : heroTitle);
            root.put("heroSubtitle", heroSubtitle == null ? "" : heroSubtitle);
            root.putArray("demoThumbnails").addAll(stringArrayNode(objectMapper, demoThumbnails));
            root.putArray("useCases").addAll(stringArrayNode(objectMapper, useCases));
            root.putArray("steps").addAll(stringArrayNode(objectMapper, steps));
            root.putArray("recommendedToolCodes").addAll(stringArrayNode(objectMapper, recommendedToolCodes));
            root.put("beforeVideoUrl", beforeVideoUrl == null ? "" : beforeVideoUrl);
            root.put("afterVideoUrl", afterVideoUrl == null ? "" : afterVideoUrl);
            return "<!-- ai-tool-ui:" + objectMapper.writeValueAsString(root) + " -->";
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String replaceOrAppendFrontendStyleMarker(String configNote,
                                                            ToolFrontendStyleConfig style,
                                                            ObjectMapper objectMapper) {
        if (configNote == null) {
            return null;
        }
        String marker = style.toConfigNoteMarker(objectMapper);
        if (marker == null) {
            return configNote;
        }
        Matcher matcher = FRONTEND_STYLE_PATTERN.matcher(configNote);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(marker));
        }
        String base = configNote.strip();
        return base.isEmpty() ? marker : base + "\n\n" + marker;
    }

    private static String preferNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static List<String> preferNonEmptyList(List<String> preferred, List<String> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        return fallback == null ? List.of() : fallback;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode stringArrayNode(ObjectMapper objectMapper,
                                                                                 List<String> values) {
        var array = objectMapper.createArrayNode();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
        return array;
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
