package com.aiminilab.aitoolmarket.tool.dto;

import java.util.List;

public record ToolDetailResponse(
        Long id,
        String toolCode,
        String toolName,
        Long categoryId,
        String categoryName,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String configNote,
        String status,
        Integer estimatedCreditCost,
        Long modelConfigId,
        String modelConfigName,
        String modelName,
        List<ToolFieldResponse> fields
) {
    public static ToolDetailResponse of(ToolSummaryResponse summary, List<ToolFieldResponse> fields) {
        return new ToolDetailResponse(
                summary.id(),
                summary.toolCode(),
                summary.toolName(),
                summary.categoryId(),
                summary.categoryName(),
                summary.description(),
                summary.coverUrl(),
                summary.toolType(),
                summary.inputModality(),
                summary.outputModality(),
                summary.configNote(),
                summary.status(),
                summary.estimatedCreditCost(),
                summary.modelConfigId(),
                summary.modelConfigName(),
                summary.modelName(),
                fields
        );
    }
}
