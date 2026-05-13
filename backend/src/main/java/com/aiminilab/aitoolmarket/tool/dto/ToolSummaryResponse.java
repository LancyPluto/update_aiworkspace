package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;

public record ToolSummaryResponse(
        Long id,
        String toolCode,
        String toolName,
        Long categoryId,
        String categoryName,
        String description,
        String coverUrl,
        String status,
        Integer estimatedCreditCost,
        Long modelConfigId,
        String modelConfigName,
        String modelName
) {
    public static ToolSummaryResponse from(AiTool tool) {
        return new ToolSummaryResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryId(),
                tool.getCategoryName(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getStatus(),
                tool.getEstimatedCreditCost(),
                tool.getModelConfigId(),
                tool.getModelConfigName(),
                tool.getModelName()
        );
    }
}
