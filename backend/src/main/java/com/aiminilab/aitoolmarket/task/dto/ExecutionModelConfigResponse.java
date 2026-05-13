package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

public record ExecutionModelConfigResponse(
        Long id,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String minimaxGroupId,
        Integer timeoutSeconds
) {
    public static ExecutionModelConfigResponse from(AgentModelConfig config) {
        if (config == null) {
            return null;
        }
        return new ExecutionModelConfigResponse(
                config.getId(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds()
        );
    }
}
