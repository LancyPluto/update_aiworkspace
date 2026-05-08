package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ToolFieldResponse(
        String fieldKey,
        String fieldName,
        String fieldType,
        String placeholder,
        JsonNode options,
        Boolean required,
        Integer sortOrder
) {
    public static ToolFieldResponse from(ToolFieldItem item, ObjectMapper objectMapper) {
        return new ToolFieldResponse(
                item.getFieldKey(),
                item.getFieldName(),
                item.getFieldType(),
                item.getPlaceholder(),
                parseJson(item.getOptionsJson(), objectMapper),
                item.getRequired(),
                item.getSortOrder()
        );
    }

    private static JsonNode parseJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }
}
