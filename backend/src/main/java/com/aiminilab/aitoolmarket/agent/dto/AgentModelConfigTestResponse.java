package com.aiminilab.aitoolmarket.agent.dto;

public record AgentModelConfigTestResponse(
        boolean success,
        String provider,
        String modelName,
        Long latencyMs,
        String message,
        String sample
) {
}
