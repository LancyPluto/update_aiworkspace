package com.aiminilab.aitoolmarket.tool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicToolFieldResponse(
        String fieldKey,
        String fieldName,
        String fieldType,
        String placeholder,
        JsonNode options,
        Boolean required,
        Boolean executionRequired,
        Boolean userRequired,
        String defaultValue,
        Integer sortOrder
) {
    public static PublicToolFieldResponse from(ToolFieldResponse field) {
        return new PublicToolFieldResponse(
                field.fieldKey(),
                field.fieldName(),
                publicFieldType(field.fieldType()),
                field.placeholder(),
                PublicToolFieldOptionsSanitizer.sanitize(field.options()),
                field.required(),
                field.executionRequired(),
                field.userRequired(),
                field.defaultValue(),
                field.sortOrder()
        );
    }

    private static String publicFieldType(String fieldType) {
        return fieldType == null ? null : fieldType.trim().toLowerCase(Locale.ROOT);
    }
}
