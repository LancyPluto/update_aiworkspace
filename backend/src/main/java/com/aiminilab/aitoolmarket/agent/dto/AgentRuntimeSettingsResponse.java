package com.aiminilab.aitoolmarket.agent.dto;

public record AgentRuntimeSettingsResponse(
        Integer maxModelCalls,
        Integer maxToolCalls,
        Integer maxHistoryMessages,
        Integer workingMemoryTokenBudget,
        Integer messageTokenSoftLimit,
        Integer toolOutputTokenSoftLimit,
        Integer summaryTokenLimit,
        Integer toolExecutionTimeoutSeconds,
        Integer imageToolExecutionTimeoutSeconds,
        Integer videoToolExecutionTimeoutSeconds,
        Integer musicToolExecutionTimeoutSeconds,
        Double toolPollIntervalSeconds,
        Boolean toolStreamRelayEnabled,
        Boolean productToolLoopEnabled,
        Integer productToolLoopMaxCalls,
        Boolean productToolLoopFallbackToRouter,
        String intelligenceLevel
) {
}
