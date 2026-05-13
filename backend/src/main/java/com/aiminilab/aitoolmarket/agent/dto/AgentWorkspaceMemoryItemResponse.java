package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;

public record AgentWorkspaceMemoryItemResponse(
        Long id,
        Long workspaceId,
        Long userId,
        String memoryType,
        String title,
        String content,
        Long sourceRunId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
