package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;

public record InternalWorkspaceMemoryItemResponse(
        Long id,
        Long workspaceId,
        Long sourceRunId,
        String title,
        String content,
        String memoryType,
        String status,
        int score,
        LocalDateTime updatedAt
) {
}
