package com.aiminilab.aitoolmarket.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentToolFieldDescriptorResponse(
        String fieldKey,
        String fieldName,
        String fieldType,
        String description,
        JsonNode options,
        Boolean required,
        Boolean executionRequired,
        Boolean userRequired,
        String defaultValue,
        String agentFillStrategy,
        String riskLevel,
        Integer sortOrder
) {
}
