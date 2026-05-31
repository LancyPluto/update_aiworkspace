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
        Long sourceMessageId,
        Long sourceToolCallId,
        Integer importance,
        Double confidence,
        Boolean pinned,
        String tagsJson,
        String metadataJson,
        LocalDateTime lastAccessedAt,
        Integer accessCount,
        LocalDateTime expiresAt,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
