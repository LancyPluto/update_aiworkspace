package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

public record InternalWorkspaceMemoryItemResponse(
        Long id,
        Long workspaceId,
        Long userId,
        Long sourceRunId,
        Long sourceMessageId,
        Long sourceToolCallId,
        String title,
        String content,
        String memoryType,
        String status,
        int score,
        String reason,
        List<String> matchedFields,
        Integer importance,
        Double confidence,
        Boolean pinned,
        String tagsJson,
        String metadataJson,
        LocalDateTime lastAccessedAt,
        Integer accessCount,
        LocalDateTime expiresAt,
        LocalDateTime updatedAt
) {

    public InternalWorkspaceMemoryItemResponse(Long id, Long workspaceId, Long sourceRunId,
                                                String title, String content, String memoryType,
                                                String status, LocalDateTime updatedAt) {
        this(id, workspaceId, null, sourceRunId, null, null, title, content, memoryType, status,
                0, "latest", List.of(), null, null, false, null, null, null, 0, null, updatedAt);
    }

    public InternalWorkspaceMemoryItemResponse(Long id, Long workspaceId, Long sourceRunId,
                                                String title, String content, String memoryType,
                                                String status, int score, LocalDateTime updatedAt) {
        this(id, workspaceId, null, sourceRunId, null, null, title, content, memoryType, status,
                score, "fulltext", List.of("title", "content"), null, null, false, null, null, null, 0, null, updatedAt);
    }
}
