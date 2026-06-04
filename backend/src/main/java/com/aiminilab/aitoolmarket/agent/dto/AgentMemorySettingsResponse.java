package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AgentMemorySettingsResponse(
        Boolean autoSaveEnabled,
        Integer retrievalLimit,
        List<String> enabledTypes,
        String writePrompt,
        String retrievalPrompt,
        Boolean toolLoopEnabled,
        Boolean consolidationEnabled,
        Boolean consolidationLlmEnabled,
        Integer consolidationTurnInterval,
        Integer consolidationCharThreshold,
        Integer consolidationTokenThreshold,
        Integer consolidationRecentToolThreshold,
        Integer consolidationMaxContextMessages,
        String consolidationPrompt,
        Double consolidationMinConfidence,
        Double candidateConfidenceThreshold
) {
}
