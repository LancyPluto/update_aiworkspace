package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentToolPreference;

import java.time.LocalDateTime;

public record AgentToolPreferenceResponse(
        Long id,
        String toolCode,
        boolean autoCallEnabled,
        boolean disabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentToolPreferenceResponse from(AgentToolPreference preference) {
        return new AgentToolPreferenceResponse(
                preference.getId(),
                preference.getToolCode(),
                Boolean.TRUE.equals(preference.getAutoCallEnabled()),
                Boolean.TRUE.equals(preference.getDisabled()),
                preference.getCreatedAt(),
                preference.getUpdatedAt()
        );
    }
}
