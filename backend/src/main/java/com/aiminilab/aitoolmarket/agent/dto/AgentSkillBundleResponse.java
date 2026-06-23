package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AgentSkillBundleResponse(
        Long id,
        String skillCode,
        String displayName,
        String description,
        List<String> toolCodes,
        String sopRules,
        String whenToUse,
        String whenNotToUse,
        Object fieldPolicy,
        Object examples,
        String status,
        Integer version,
        String publishedAt,
        String updatedAt
) {
}
