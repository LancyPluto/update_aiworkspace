package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import java.time.LocalDateTime;

public record AgentModelConfigResponse(
        Long id,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKeyMasked,
        String minimaxGroupId,
        Integer timeoutSeconds,
        Boolean enabled,
        Boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentModelConfigResponse from(AgentModelConfig config) {
        return new AgentModelConfigResponse(
                config.getId(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                mask(config.getApiKey()),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                config.getEnabled(),
                config.getDefault(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
