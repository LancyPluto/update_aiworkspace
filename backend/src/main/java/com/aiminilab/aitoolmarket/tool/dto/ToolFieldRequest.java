package com.aiminilab.aitoolmarket.tool.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record ToolFieldRequest(
        @NotBlank String fieldKey,
        @NotBlank String fieldName,
        @NotBlank String fieldType,
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
    public String resolveOptionsJson() {
        if (optionsJson != null && !optionsJson.isBlank()) {
            return optionsJson;
        }
        if (options == null || options.isNull()) {
            return null;
        }
        if (options.isTextual()) {
            String text = options.asText();
            return text.isBlank() ? null : text;
        }
        return options.toString();
    }
}
