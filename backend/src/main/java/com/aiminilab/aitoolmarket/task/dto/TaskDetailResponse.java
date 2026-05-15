package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record TaskDetailResponse(
        Long taskId,
        String taskNo,
        Long userId,
        String toolCode,
        String toolName,
        String status,
        Integer progress,
        String progressMessage,
        String errorCode,
        String errorMessage,
        JsonNode params,
        TaskResultResponse result,
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {
    public static TaskDetailResponse of(AiTask task, JsonNode params, TaskResultResponse result) {
        return new TaskDetailResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getToolCode(),
                task.getToolName(),
                task.getStatus(),
                task.getProgress(),
                task.getProgressMessage(),
                task.getErrorCode(),
                task.getErrorMessage(),
                params,
                result,
                task.getCreatedAt(),
                task.getFinishedAt()
        );
    }
}
