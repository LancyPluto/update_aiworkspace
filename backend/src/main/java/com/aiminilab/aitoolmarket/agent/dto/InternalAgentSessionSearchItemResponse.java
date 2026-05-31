package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;

public record InternalAgentSessionSearchItemResponse(
        String itemType,
        Long id,
        Long runId,
        Long taskId,
        String role,
        String toolCode,
        String content,
        String argumentsJson,
        String resultJson,
        String errorCode,
        String errorMessage,
        int score,
        LocalDateTime createdAt
) {
}
