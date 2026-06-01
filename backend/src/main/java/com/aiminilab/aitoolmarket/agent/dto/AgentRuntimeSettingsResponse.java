package com.aiminilab.aitoolmarket.agent.dto;

public record AgentRuntimeSettingsResponse(
        Integer maxModelCalls,
        Integer maxToolCalls,
        Integer maxHistoryMessages,
        Integer toolExecutionTimeoutSeconds,
        Integer imageToolExecutionTimeoutSeconds,
        Integer videoToolExecutionTimeoutSeconds,
        Double toolPollIntervalSeconds,
        Boolean toolStreamRelayEnabled,
        Boolean productToolLoopEnabled,
        Integer productToolLoopMaxCalls,
        Boolean productToolLoopFallbackToRouter
) {
}
