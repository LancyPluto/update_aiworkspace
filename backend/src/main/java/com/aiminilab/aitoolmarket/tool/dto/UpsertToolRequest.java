package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpsertToolRequest(
        String toolCode,
        @NotBlank String toolName,
        Long categoryId,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String configNote,
        @Min(0) Integer estimatedCreditCost,
        Long modelConfigId,
        String executionHandler,
        String templateCode
) {
}
