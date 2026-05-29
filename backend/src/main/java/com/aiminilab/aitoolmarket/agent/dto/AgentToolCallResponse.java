package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;

import java.time.LocalDateTime;

public record AgentToolCallResponse(
        Long id,
        Long runId,
        String toolCode,
        Long taskId,
        String status,
        String argumentsJson,
        String resultJson,
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
    public static AgentToolCallResponse from(AgentToolCall call) {
        return new AgentToolCallResponse(
                call.getId(),
                call.getRunId(),
                call.getToolCode(),
                call.getTaskId(),
                call.getStatus(),
                call.getArgumentsJson(),
                call.getResultJson(),
                call.getErrorCode(),
                call.getErrorMessage(),
                call.getStartedAt(),
                call.getFinishedAt(),
                call.getCreatedAt()
        );
    }
}
