package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpsertFieldSchemaRequest(
        @NotBlank String schemaVersion,
        @NotEmpty @Valid List<FieldSchemaItemRequest> items
) {
}
