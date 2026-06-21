package com.aiminilab.aitoolmarket.agent.dto;

public record InternalReferenceMentionResponse(
        String token,
        String refLabel,
        String assetKey,
        String url,
        String kind,
        String source
) {
}
