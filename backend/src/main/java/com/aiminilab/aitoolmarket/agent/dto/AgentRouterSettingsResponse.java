package com.aiminilab.aitoolmarket.agent.dto;

public record AgentRouterSettingsResponse(
        Boolean enabled,
        String prompt,
        Double minConfidence,
        Boolean fallbackToRules,
        Integer historyTurns,
        Integer recentToolCallLimit
) {
}
