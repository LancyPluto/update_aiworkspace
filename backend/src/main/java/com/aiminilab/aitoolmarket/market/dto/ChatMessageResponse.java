package com.aiminilab.aitoolmarket.market.dto;

public record ChatMessageResponse(
        MessageResponse userMessage,
        MessageResponse assistantMessage
) {
}
