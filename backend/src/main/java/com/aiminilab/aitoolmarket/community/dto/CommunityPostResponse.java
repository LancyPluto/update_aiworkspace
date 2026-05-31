package com.aiminilab.aitoolmarket.community.dto;

import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

public record CommunityPostResponse(
        Long id,
        Long userId,
        String authorNickname,
        String authorAvatarUrl,
        Long taskId,
        String modality,
        String coverUrl,
        String title,
        String description,
        Boolean promptVisible,
        String prompt,
        String promptPreview,
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int PROMPT_PREVIEW_MAX = 160;

    public static CommunityPostResponse from(CommunityPost post, boolean liked, boolean favorited) {
        return from(post, liked, favorited, List.of());
    }

    public static CommunityPostResponse from(CommunityPost post, boolean liked, boolean favorited, List<String> tags) {
        return from(post, liked, favorited, tags, null, null, post.getPromptSnapshot());
    }

    public static CommunityPostResponse from(CommunityPost post,
                                             boolean liked,
                                             boolean favorited,
                                             List<String> tags,
                                             String authorNickname,
                                             String authorAvatarUrl) {
        return from(post, liked, favorited, tags, authorNickname, authorAvatarUrl, post.getPromptSnapshot());
    }

    public static CommunityPostResponse from(CommunityPost post,
                                             boolean liked,
                                             boolean favorited,
                                             List<String> tags,
                                             String authorNickname,
                                             String authorAvatarUrl,
                                             String promptSnapshot) {
        return new CommunityPostResponse(
                post.getId(),
                post.getUserId(),
                authorNickname,
                authorAvatarUrl,
                post.getTaskId(),
                post.getModality(),
                post.getCoverUrl(),
                post.getTitle(),
                post.getDescription(),
                Boolean.TRUE.equals(post.getPromptVisible()),
                Boolean.TRUE.equals(post.getPromptVisible()) ? promptSnapshot : null,
                resolvePromptPreview(promptSnapshot),
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

    static String resolvePromptPreview(String snapshot) {
        String extracted = extractPromptText(snapshot);
        if (extracted == null || extracted.isBlank()) {
            return null;
        }
        String normalized = extracted.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= PROMPT_PREVIEW_MAX) {
            return normalized;
        }
        return normalized.substring(0, PROMPT_PREVIEW_MAX - 1).trim() + "…";
    }

    private static String extractPromptText(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        String trimmed = snapshot.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            if (root.isTextual()) {
                return root.asText();
            }
            for (String key : List.of("prompt", "description", "text", "content", "message", "videoTopic", "productName")) {
                JsonNode node = root.path(key);
                if (node.isTextual() && !node.asText().isBlank()) {
                    return node.asText().trim();
                }
            }
        } catch (Exception ignored) {
            // Fall back to raw snapshot below.
        }
        return trimmed;
    }
}
