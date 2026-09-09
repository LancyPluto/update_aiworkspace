package com.aiminilab.aitoolmarket.agent.dto;

public record AgentRouterSettingsResponse(
        Boolean enabled,
        String prompt,
        Double minConfidence,
        Integer historyTurns,
        Integer recentToolCallLimit
) {
}
