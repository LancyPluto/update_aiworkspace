package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.task.entity.AiTask;

public record TaskStatusResponse(
        Long taskId,
        String taskNo,
        String toolCode,
        String status,
        Integer progress,
        String progressMessage
) {
    public static TaskStatusResponse from(AiTask task) {
        return new TaskStatusResponse(
                task.getId(),
                task.getTaskNo(),
                task.getToolCode(),
                task.getStatus(),
                task.getProgress(),
                task.getProgressMessage()
        );
    }
}
