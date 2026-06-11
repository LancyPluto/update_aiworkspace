package com.aiminilab.aitoolmarket.tool.dto;

public record ToolPromptDraftFieldRequest(
        String fieldKey,
        String fieldName,
        String fieldType,
        String placeholder,
        Boolean required
) {
}
