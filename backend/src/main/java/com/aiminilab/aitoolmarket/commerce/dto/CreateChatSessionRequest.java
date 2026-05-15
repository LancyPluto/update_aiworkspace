package com.aiminilab.aitoolmarket.commerce.dto;

public record CreateChatSessionRequest(
        Long poolId,
        Long nodeId,
        String title
) {
}
