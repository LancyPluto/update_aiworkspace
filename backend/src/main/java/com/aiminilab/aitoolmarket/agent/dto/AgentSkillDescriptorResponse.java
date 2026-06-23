package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AgentSkillDescriptorResponse(
        String skillCode,
        String displayName,
        String description,
        List<String> toolCodes,
        Integer version
) {
}
