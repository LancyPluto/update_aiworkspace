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
        String status,
        Integer estimatedCreditCost,
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
                summary.status(),
                summary.estimatedCreditCost(),
                fields
        );
    }
}
