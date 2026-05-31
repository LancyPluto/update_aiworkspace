package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record InternalAgentSessionSearchRequest(
        Long userId,
        Long sessionId,
        String query,
        String toolCode,
        @Min(value = 1, message = "limit must be positive")
        @Max(value = 20, message = "limit is too large")
        Integer limit
) {
}
