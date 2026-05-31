package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;

public record InternalCreateWorkspaceMemoryCandidateRequest(
        Long userId,
        String action,
        String memoryType,
        String title,
        String content,
        Long sourceRunId,
        Long sourceMessageId,
        Long sourceToolCallId,
        Integer importance,
        Double confidence,
        String reason,
        String tagsJson,
        String metadataJson,
        LocalDateTime expiresAt
) {
}
