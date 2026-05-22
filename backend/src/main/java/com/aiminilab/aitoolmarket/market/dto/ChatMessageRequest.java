package com.aiminilab.aitoolmarket.market.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ChatMessageRequest(
        @NotBlank String toolId,
        @NotBlank String sessionId,
        @NotBlank String content,
        List<String> attachments,
        Map<String, Object> params
) {
}
