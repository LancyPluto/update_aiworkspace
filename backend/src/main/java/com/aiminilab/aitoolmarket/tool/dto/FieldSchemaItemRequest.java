package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record FieldSchemaItemRequest(
        @NotBlank String fieldKey,
        @NotBlank String fieldName,
        @NotBlank String fieldType,
        String placeholder,
        String optionsJson,
        Boolean required,
        Integer sortOrder
) {
}
