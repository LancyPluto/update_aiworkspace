package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;

public record AdminAgentRunListItemResponse(
        Long id,
        Long sessionId,
        Long userId,
        String status,
        String intent,
        String modelProviderCode,
        String modelName,
        Integer estimatedCredits,
        Integer consumedCredits,
        String errorCode,
        String errorMessage,
        Long eventCount,
        Long toolCallCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
