package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
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
        String executionHandler,
        String inputModality,
        String outputModality,
        String status,
        Long routeAttemptId,
        String traceId,
        JsonNode params,
        ExecutionModelConfigResponse modelConfig,
        ModelExecutionSnapshot modelSnapshot,
        String modelProviderCode,
        String modelName,
        String systemPrompt,
        String userPromptTemplate,
        List<ToolFieldResponse> fields,
        JsonNode providerCheckpoint,
        Integer providerCheckpointVersion
) {
    public static ExecutionContextResponse of(AiTask task, JsonNode params, ExecutionModelConfigResponse modelConfig,
                                              List<ToolFieldResponse> fields) {
        return of(task, params, modelConfig, null, fields, null, null);
    }

    public static ExecutionContextResponse of(AiTask task, JsonNode params, ExecutionModelConfigResponse modelConfig,
                                              List<ToolFieldResponse> fields, String systemPrompt,
                                              String userPromptTemplate) {
        return of(task, params, modelConfig, null, fields, systemPrompt, userPromptTemplate);
    }

    public static ExecutionContextResponse of(AiTask task, JsonNode params, ExecutionModelConfigResponse modelConfig,
                                              ModelExecutionSnapshot modelSnapshot, List<ToolFieldResponse> fields) {
        return of(task, params, modelConfig, modelSnapshot, fields, null, null);
    }

    public static ExecutionContextResponse of(AiTask task, JsonNode params, ExecutionModelConfigResponse modelConfig,
                                              ModelExecutionSnapshot modelSnapshot, List<ToolFieldResponse> fields,
                                              String systemPrompt, String userPromptTemplate) {
        return new ExecutionContextResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getToolId(),
                task.getToolCode(),
                task.getToolName(),
                defaultValue(task.getToolType(), "TEXT_GENERATION"),
                resolveExecutionHandler(task),
                defaultValue(task.getInputModality(), "TEXT"),
                defaultValue(task.getOutputModality(), "TEXT"),
                task.getStatus(),
                task.getCurrentRouteAttemptId(),
                MDC.get("traceId"),
                params,
                modelConfig,
                modelSnapshot,
                modelConfig == null ? null : modelConfig.provider(),
                modelConfig == null ? null : modelConfig.modelName(),
                blankToNull(systemPrompt),
                blankToNull(userPromptTemplate),
                fields,
                null,
                task.getProviderCheckpointVersion() == null ? 0 : task.getProviderCheckpointVersion()
        );
    }

    public ExecutionContextResponse withProviderCheckpoint(JsonNode checkpoint) {
        return new ExecutionContextResponse(
                taskId, taskNo, userId, toolId, toolCode, toolName, toolType, executionHandler,
                inputModality, outputModality, status, routeAttemptId, traceId, params, modelConfig, modelSnapshot,
                modelProviderCode, modelName, systemPrompt, userPromptTemplate, fields,
                checkpoint, providerCheckpointVersion
        );
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String resolveExecutionHandler(AiTask task) {
        if (task.getExecutionHandler() != null && !task.getExecutionHandler().isBlank()) {
            return task.getExecutionHandler();
        }
        if ("digital_human_agent".equals(task.getToolCode())) {
            return "DIGITAL_HUMAN";
        }
        String toolType = task.getToolType();
        if (toolType != null && !toolType.isBlank()) {
            return toolType.trim().toUpperCase();
        }
        return "TEXT_GENERATION";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
