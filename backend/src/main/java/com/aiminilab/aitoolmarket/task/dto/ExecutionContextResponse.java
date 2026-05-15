package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.MDC;

import java.util.List;

public record ExecutionContextResponse(
        Long taskId,
        String taskNo,
        Long userId,
        Long toolId,
        String toolCode,
        String toolName,
        String toolType,
        String inputModality,
        String outputModality,
        String status,
        String traceId,
        JsonNode params,
        ExecutionModelConfigResponse modelConfig,
        String modelProviderCode,
        String modelName,
        List<ToolFieldResponse> fields
) {
    public static ExecutionContextResponse of(AiTask task, JsonNode params, ExecutionModelConfigResponse modelConfig,
                                              List<ToolFieldResponse> fields) {
        return new ExecutionContextResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getToolId(),
                task.getToolCode(),
                task.getToolName(),
                defaultValue(task.getToolType(), "TEXT_GENERATION"),
                defaultValue(task.getInputModality(), "TEXT"),
                defaultValue(task.getOutputModality(), "TEXT"),
                task.getStatus(),
                MDC.get("traceId"),
                params,
                modelConfig,
                modelConfig == null ? null : modelConfig.provider(),
                modelConfig == null ? null : modelConfig.modelName(),
                fields
        );
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
