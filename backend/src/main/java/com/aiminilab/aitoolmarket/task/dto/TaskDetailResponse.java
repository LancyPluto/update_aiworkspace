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
        String toolType,
        String inputModality,
        String outputModality,
        String status,
        Integer progress,
        String progressMessage,
        String errorCode,
        String errorMessage,
        JsonNode params,
        TaskResultResponse result,
        AgentTaskSourceResponse agentSource,
        Long communityPostId,
        Integer consumedCredits,
        LocalDateTime createdAt,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    public static TaskDetailResponse of(AiTask task, JsonNode params, TaskResultResponse result,
                           Integer consumedCredits) {
        return of(task, params, result, null, consumedCredits);
    }

    public static TaskDetailResponse of(AiTask task, JsonNode params, TaskResultResponse result,
                           AgentTaskSourceResponse agentSource, Integer consumedCredits) {
        return of(task, params, result, agentSource, null, consumedCredits);
    }

    public static TaskDetailResponse of(AiTask task, JsonNode params, TaskResultResponse result,
                           AgentTaskSourceResponse agentSource, Long communityPostId, Integer consumedCredits) {
        return new TaskDetailResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getToolCode(),
                task.getToolName(),
                defaultValue(task.getToolType(), "TEXT_GENERATION"),
                defaultValue(task.getInputModality(), "TEXT"),
                defaultValue(task.getOutputModality(), "TEXT"),
                task.getStatus(),
                task.getProgress(),
                task.getProgressMessage(),
                task.getErrorCode(),
                task.getErrorMessage(),
                params,
                result,
                agentSource,
                communityPostId,
                consumedCredits,
                task.getCreatedAt(),
                task.getQueuedAt(),
                task.getStartedAt(),
                task.getFinishedAt()
        );
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
