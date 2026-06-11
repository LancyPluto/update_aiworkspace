package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.aiminilab.aitoolmarket.tool.support.ToolKindSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSummaryResponse(
        Long id,
        String toolCode,
        String toolName,
        Long categoryId,
        String categoryCode,
        String categoryName,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String toolKind,
        String configNote,
        String status,
        Integer estimatedCreditCost,
        Long modelConfigId,
        String modelConfigName,
        String modelName,
        String executionHandler,
        ToolFrontendStyleConfig frontendStyle
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
                tool.getCategoryCode(),
                tool.getCategoryName(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                ToolKindSupport.resolve(tool),
                tool.getConfigNote(),
                tool.getStatus(),
                estimatedCreditCost,
                tool.getModelConfigId(),
                tool.getModelConfigName(),
                tool.getModelName(),
                tool.getExecutionHandler(),
                null
        );
    }

    public static ToolSummaryResponse publicFrom(AiTool tool, Integer estimatedCreditCost, ObjectMapper objectMapper) {
        return new ToolSummaryResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryId(),
                tool.getCategoryCode(),
                tool.getCategoryName(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                ToolKindSupport.resolve(tool),
                null,
                tool.getStatus(),
                estimatedCreditCost,
                tool.getModelConfigId(),
                tool.getModelConfigName(),
                tool.getModelName(),
                tool.getExecutionHandler(),
                ToolFrontendStyleConfig.fromConfigNote(tool.getConfigNote(), objectMapper)
        );
    }

    public ToolSummaryResponse withSanitizedCoverUrl(String coverUrl) {
        if (coverUrl == null ? this.coverUrl == null : coverUrl.equals(this.coverUrl)) {
            return this;
        }
        return new ToolSummaryResponse(
                id,
                toolCode,
                toolName,
                categoryId,
                categoryCode,
                categoryName,
                description,
                coverUrl,
                toolType,
                inputModality,
                outputModality,
                toolKind,
                configNote,
                status,
                estimatedCreditCost,
                modelConfigId,
                modelConfigName,
                modelName,
                executionHandler,
                frontendStyle
        );
    }
}
