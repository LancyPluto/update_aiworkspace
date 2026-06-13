package com.aiminilab.aitoolmarket.community.dto;

import java.time.LocalDateTime;

public record CommunityPostReportResponse(
        Long id,
        Long postId,
        String postTitle,
        String postCoverUrl,
        String postStatus,
        Long reporterUserId,
        String reason,
        String status,
        String adminNote,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt
) {
}
