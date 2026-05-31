package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;

public record InternalCreateWorkspaceMemoryRequest(
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
        LocalDateTime expiresAt,
        Long userId
) {
}
