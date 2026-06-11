package com.aiminilab.aitoolmarket.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ToolRuntimeConfig(
        String toolKind,
        String systemPrompt,
        String adminPrompt,
        List<UserInput> userInputs
) {
    private static final Pattern RUNTIME_PATTERN = Pattern.compile("<!--\\s*ai-tool-runtime:([\\s\\S]*?)\\s*-->");

    public record UserInput(
            String fieldKey,
            String fieldName,
            String fieldType,
            String placeholder,
            boolean required,
            int sortOrder
    ) {
    }

    public static ToolRuntimeConfig empty() {
        return new ToolRuntimeConfig("", "", "", List.of());
    }

    public static ToolRuntimeConfig fromConfigNote(String configNote, ObjectMapper objectMapper) {
        if (configNote == null || configNote.isBlank()) {
            return empty();
        }
        Matcher matcher = RUNTIME_PATTERN.matcher(configNote);
        if (!matcher.find()) {
            return empty();
        }
        try {
            JsonNode node = objectMapper.readTree(matcher.group(1));
            return new ToolRuntimeConfig(
                    text(node, "toolKind"),
                    text(node, "systemPrompt"),
                    text(node, "adminPrompt"),
                    inputs(node.get("userInputs"))
            );
        } catch (Exception ignored) {
            return empty();
        }
    }

    public boolean hasAdminPrompt() {
        return adminPrompt != null && !adminPrompt.isBlank();
    }

    private static List<UserInput> inputs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<UserInput> values = new ArrayList<>();
        int fallbackOrder = 1;
        for (JsonNode item : node) {
            String fieldKey = text(item, "fieldKey");
            if (fieldKey.isBlank()) {
                continue;
            }
            String fieldName = text(item, "fieldName");
            String fieldType = text(item, "fieldType");
            values.add(new UserInput(
                    fieldKey,
                    fieldName.isBlank() ? fieldKey : fieldName,
                    fieldType.isBlank() ? "text" : fieldType,
                    text(item, "placeholder"),
                    item.path("required").asBoolean(true),
                    item.path("sortOrder").asInt(fallbackOrder)
            ));
            fallbackOrder++;
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            return "";
        }
        return value.asText().trim();
    }
}
