package com.aiminilab.aitoolmarket.agent.dto;

public record AgentToolPickerItemResponse(
        String toolCode,
        String toolName,
        String description,
        String outputModality,
        String coverUrl,
        Integer estimatedCreditCost,
        boolean autoCallEnabled,
        boolean disabled
) {
}
