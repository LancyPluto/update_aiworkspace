package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;
import com.aiminilab.aitoolmarket.task.support.TaskFailureMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record TaskDetailResponse(
        Long taskId,
        String taskNo,
        Long userId,
        String toolCode,
        String toolName,
        Long modelConfigId,
        String modelConfigName,
        String modelName,
        String toolType,
        String inputModality,
        String outputModality,
        String status,
        Integer progress,
        String progressMessage,
        String errorCode,
        String errorMessage,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String developerMessage,
        String failureTraceId,
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
        return of(task, params, result, agentSource, communityPostId, consumedCredits, false);
    }

    public static TaskDetailResponse of(AiTask task, JsonNode params, TaskResultResponse result,
                           AgentTaskSourceResponse agentSource, Long communityPostId, Integer consumedCredits,
                           boolean forAdmin) {
        return new TaskDetailResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getToolCode(),
                task.getToolName(),
                task.getModelConfigId(),
                defaultValue(task.getModelConfigName(), task.getModelName()),
                task.getModelName(),
                defaultValue(task.getToolType(), "TEXT_GENERATION"),
                defaultValue(task.getInputModality(), "TEXT"),
                defaultValue(task.getOutputModality(), "TEXT"),
                task.getStatus(),
                task.getProgress(),
                TaskFailureMessage.userFacingProgressMessage(task.getErrorCode(), task.getProgressMessage()),
                responseErrorCode(task.getErrorCode()),
                responseErrorMessage(task, forAdmin),
                responseDeveloperMessage(task, forAdmin),
                task.getFailureTraceId(),
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

    private static String responseErrorMessage(AiTask task, boolean forAdmin) {
        if (task.getErrorCode() == null
                && task.getErrorMessage() == null
                && task.getUserMessage() == null
                && task.getDeveloperMessage() == null) {
            return null;
        }
        if (forAdmin) {
            String candidate = task.getDeveloperMessage() == null || task.getDeveloperMessage().isBlank()
                    ? task.getErrorMessage()
                    : task.getDeveloperMessage();
            return candidate == null
                    ? null
                    : ErrorMessageSanitizer.sanitizeDeveloperMessage(candidate, "Task execution failed");
        }
        String candidate = task.getUserMessage() == null || task.getUserMessage().isBlank()
                ? TaskFailureMessage.userFacingProgressMessage(task.getErrorCode(), task.getProgressMessage())
                : task.getUserMessage();
        return ErrorMessageSanitizer.sanitizeUserMessage(candidate, "任务执行失败，请稍后重试");
    }

    private static String responseDeveloperMessage(AiTask task, boolean forAdmin) {
        if (!forAdmin) {
            return null;
        }
        String candidate = task.getDeveloperMessage() == null || task.getDeveloperMessage().isBlank()
                ? task.getErrorMessage()
                : task.getDeveloperMessage();
        return candidate == null
                ? null
                : ErrorMessageSanitizer.sanitizeDeveloperMessage(candidate, "Task execution failed");
    }

    private static String responseErrorCode(String errorCode) {
        return errorCode != null && "MODEL_TIMEOUT".equalsIgnoreCase(errorCode.trim())
                ? "MODEL_004"
                : errorCode;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
