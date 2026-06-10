package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.common.util.Utf8TextRepair;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.entity.ToolTemplateField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ToolFieldResponse(
        String fieldKey,
        String fieldName,
        String fieldType,
        String placeholder,
        JsonNode options,
        String optionsJson,
        Boolean required,
        Boolean executionRequired,
        Boolean userRequired,
        String defaultValue,
        String agentFillStrategy,
        String riskLevel,
        Integer sortOrder
) {
    public static ToolFieldResponse from(ToolFieldItem item, ObjectMapper objectMapper) {
        return new ToolFieldResponse(
                item.getFieldKey(),
                Utf8TextRepair.repairIfNeeded(item.getFieldName()),
                item.getFieldType(),
                Utf8TextRepair.repairIfNeeded(item.getPlaceholder()),
                parseJson(item.getOptionsJson(), objectMapper),
                item.getOptionsJson(),
                item.getRequired(),
                item.getExecutionRequired(),
                item.getUserRequired(),
                item.getDefaultValue(),
                item.getAgentFillStrategy(),
                item.getRiskLevel(),
                item.getSortOrder()
        );
    }

    public static ToolFieldResponse fromTemplateField(ToolTemplateField item, ObjectMapper objectMapper) {
        return new ToolFieldResponse(
                item.getFieldKey(),
                Utf8TextRepair.repairIfNeeded(item.getFieldName()),
                item.getFieldType(),
                Utf8TextRepair.repairIfNeeded(item.getPlaceholder()),
                parseJson(item.getOptionsJson(), objectMapper),
                item.getOptionsJson(),
                item.getRequired(),
                item.getRequired(),
                item.getRequired(),
                null,
                item.getRequired() != null && item.getRequired() ? "ask_user" : "default",
                "LOW",
                item.getSortOrder()
        );
    }

    private static JsonNode parseJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                return objectMapper.readTree(node.asText());
            }
            return node;
        } catch (Exception exception) {
            return null;
        }
    }
}
