package com.aiminilab.aitoolmarket.agent.dto;

public record AdminAgentRunStatsResponse(
        Long totalRuns,
        Long activeRuns,
        Long successRuns,
        Long failedRuns,
        Long cancelledRuns,
        Long toolCalls,
        Long totalConsumedCredits
) {
}
