package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AgentMemorySettingsResponse(
        Boolean autoSaveEnabled,
        Integer retrievalLimit,
        List<String> enabledTypes,
        String writePrompt,
        String retrievalPrompt
) {
}
