package com.aiminilab.aitoolmarket.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentToolFieldDescriptorResponse(
        String fieldKey,
        String fieldName,
        String fieldType,
        String description,
        JsonNode options,
        Boolean required,
        Integer sortOrder
) {
}
