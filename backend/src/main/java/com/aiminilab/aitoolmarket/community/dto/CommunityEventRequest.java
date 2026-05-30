package com.aiminilab.aitoolmarket.community.dto;

public record CommunityEventRequest(
        Long postId,
        String eventType,
        String source,
        String toolCode,
        Long taskId,
        Integer credits
) {
}
