package com.aiminilab.aitoolmarket.tool.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record ToolFieldRequest(
        @NotBlank String fieldKey,
        @NotBlank String fieldName,
        @NotBlank String fieldType,
        String placeholder,
        JsonNode options,
        Boolean required,
        Integer sortOrder
) {
}
