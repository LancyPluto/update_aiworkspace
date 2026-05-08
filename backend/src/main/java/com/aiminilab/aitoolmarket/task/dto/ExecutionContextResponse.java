package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record ExecutionContextResponse(
        Long taskId,
        String taskNo,
        Long userId,
        Long toolId,
        String toolCode,
        String toolName,
        String status,
        JsonNode params,
        List<ToolFieldResponse> fields
) {
    public static ExecutionContextResponse of(AiTask task, JsonNode params, List<ToolFieldResponse> fields) {
        return new ExecutionContextResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getToolId(),
                task.getToolCode(),
                task.getToolName(),
                task.getStatus(),
                params,
                fields
        );
    }
}
