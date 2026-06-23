package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record UpdateAgentSkillBundleRequest(
        String displayName,
        String description,
        List<String> toolCodes,
        String sopRules,
        String whenToUse,
        String whenNotToUse,
        Object fieldPolicy,
        Object examples
) {
}
