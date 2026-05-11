package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentRun;

import java.time.LocalDateTime;

public record AgentRunResponse(
        Long id,
        Long sessionId,
        String status,
        String intent,
        String modelProviderCode,
        String modelName,
        Integer estimatedCredits,
        Integer consumedCredits,
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(
                run.getId(),
                run.getSessionId(),
                run.getStatus(),
                run.getIntent(),
                run.getModelProviderCode(),
                run.getModelName(),
                run.getEstimatedCredits(),
                run.getConsumedCredits(),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }
}
