package com.aiminilab.aitoolmarket.community.dto;

public record PublishPostRequest(
        Long taskId,
        String title,
        String description,
        Boolean promptVisible,
        String topic,
        java.util.List<String> tags
) {
}
