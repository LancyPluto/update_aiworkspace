package com.aiminilab.aitoolmarket.community.dto;

public record UpdateCommunityPostRequest(
        String title,
        String description,
        Boolean promptVisible,
        String topic,
        java.util.List<String> tags
) {
}
