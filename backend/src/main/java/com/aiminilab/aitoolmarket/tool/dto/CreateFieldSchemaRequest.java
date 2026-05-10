package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateFieldSchemaRequest(
        @NotBlank String schemaVersion,
        @NotEmpty @Valid List<ToolFieldRequest> fields
) {
}
