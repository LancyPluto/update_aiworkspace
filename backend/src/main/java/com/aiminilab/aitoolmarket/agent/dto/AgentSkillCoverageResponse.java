package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AgentSkillCoverageResponse(String skillCode, String displayName, String status, Integer version,
                                         List<String> toolCodes, long recentHydrationCount) {}
