package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpsertToolRequest(
        String toolCode,
        @NotBlank String toolName,
        @NotNull Long categoryId,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String configNote,
        @NotNull @Min(0) Integer estimatedCreditCost,
        Long modelConfigId
) {
}
