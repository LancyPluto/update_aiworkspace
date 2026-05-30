package com.aiminilab.aitoolmarket.community.dto;

import com.aiminilab.aitoolmarket.community.entity.CommunityPost;

import java.time.LocalDateTime;
import java.util.List;

public record CommunityPostResponse(
        Long id,
        Long userId,
        Long taskId,
        String modality,
        String coverUrl,
        String title,
        String description,
        Boolean promptVisible,
        String prompt,
        String toolCode,
        String toolName,
        String status,
        Boolean featured,
        Boolean pinned,
        String topic,
        List<String> tags,
        Long sameStyleCount,
        String auditStatus,
        String auditReason,
        Long viewCount,
        Long detailClickCount,
        Long shareCount,
        Long qualityScore,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean favorited,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommunityPostResponse from(CommunityPost post, boolean liked, boolean favorited) {
        return from(post, liked, favorited, List.of());
    }

    public static CommunityPostResponse from(CommunityPost post, boolean liked, boolean favorited, List<String> tags) {
        return new CommunityPostResponse(
                post.getId(),
                post.getUserId(),
                post.getTaskId(),
                post.getModality(),
                post.getCoverUrl(),
                post.getTitle(),
                post.getDescription(),
                Boolean.TRUE.equals(post.getPromptVisible()),
                Boolean.TRUE.equals(post.getPromptVisible()) ? post.getPromptSnapshot() : null,
                post.getToolCode(),
                post.getToolName(),
                post.getStatus(),
                Boolean.TRUE.equals(post.getFeatured()),
                Boolean.TRUE.equals(post.getPinned()),
                post.getTopic(),
                tags == null ? List.of() : tags,
                post.getSameStyleCount() == null ? 0L : post.getSameStyleCount(),
                post.getAuditStatus(),
                post.getAuditReason(),
                zero(post.getViewCount()),
                zero(post.getDetailClickCount()),
                zero(post.getShareCount()),
                zero(post.getQualityScore()),
                zero(post.getLikeCount()),
                zero(post.getFavoriteCount()),
                liked,
                favorited,
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }

    private static Long zero(Long value) {
        return value == null ? 0L : value;
    }
}
