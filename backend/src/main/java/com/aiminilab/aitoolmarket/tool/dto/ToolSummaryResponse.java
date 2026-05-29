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
        String toolType,
        String inputModality,
        String outputModality,
        String configNote,
        String status,
        Integer estimatedCreditCost,
        Long modelConfigId,
        String modelConfigName,
        String modelName,
        String executionHandler
) {
    public static ToolSummaryResponse from(AiTool tool) {
        return from(tool, tool.getEstimatedCreditCost());
    }

    public static ToolSummaryResponse from(AiTool tool, Integer estimatedCreditCost) {
        return new ToolSummaryResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryId(),
                tool.getCategoryName(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                tool.getConfigNote(),
                tool.getStatus(),
                estimatedCreditCost,
                tool.getModelConfigId(),
                tool.getModelConfigName(),
                tool.getModelName(),
                tool.getExecutionHandler()
        );
    }
}
