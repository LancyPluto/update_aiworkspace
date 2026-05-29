package com.aiminilab.aitoolmarket.task.dto;

public record AgentTaskSourceResponse(
        Long runId,
        Long toolCallId,
        String toolCode
) {
}
