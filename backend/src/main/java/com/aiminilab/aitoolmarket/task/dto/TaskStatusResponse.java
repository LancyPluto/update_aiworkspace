package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.support.TaskFailureMessage;
import com.fasterxml.jackson.databind.JsonNode;

public record TaskStatusResponse(
        Long taskId,
        String taskNo,
        String toolCode,
        String status,
        Integer progress,
        String progressMessage,
        JsonNode workflowPreview
) {
    public static TaskStatusResponse from(AiTask task) {
        return from(task, null);
    }

    public static TaskStatusResponse from(AiTask task, JsonNode workflowPreview) {
        return new TaskStatusResponse(
                task.getId(),
                task.getTaskNo(),
                task.getToolCode(),
                task.getStatus(),
                task.getProgress(),
                TaskFailureMessage.userFacingProgressMessage(task.getErrorCode(), task.getProgressMessage()),
                workflowPreview
        );
    }
}
