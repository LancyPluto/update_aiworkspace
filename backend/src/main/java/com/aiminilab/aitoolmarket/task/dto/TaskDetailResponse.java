package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public record TaskDetailResponse(
        Long taskId,
        String taskNo,
        Long userId,
        String userNickname,
        String toolCode,
        String toolName,
        String status,
        Integer progress,
        String progressMessage,
        Integer consumedCredits,
        String errorCode,
        String errorMessage,
        JsonNode params,
        TaskResultResponse result,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        List<TaskLogResponse> logs,
        List<CreditLogResponse> creditLogs
) {
    public static TaskDetailResponse of(AiTask task,
                                        JsonNode params,
                                        TaskResultResponse result,
                                        Integer consumedCredits,
                                        List<TaskLogResponse> logs,
                                        List<CreditLogResponse> creditLogs) {
        return new TaskDetailResponse(
                task.getId(),
                task.getTaskNo(),
                task.getUserId(),
                task.getUserNickname(),
                task.getToolCode(),
                task.getToolName(),
                task.getStatus(),
                task.getProgress(),
                task.getProgressMessage(),
                consumedCredits,
                task.getErrorCode(),
                task.getErrorMessage(),
                params,
                result,
                task.getCreatedAt(),
                task.getFinishedAt(),
                logs,
                creditLogs
        );
    }
}
