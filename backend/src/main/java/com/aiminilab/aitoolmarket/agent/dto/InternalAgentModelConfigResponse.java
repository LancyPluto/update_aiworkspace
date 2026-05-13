package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

public record InternalAgentModelConfigResponse(
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String minimaxGroupId,
        Integer timeoutSeconds,
        Boolean enabled
) {
    public static InternalAgentModelConfigResponse from(AgentModelConfig config) {
        return new InternalAgentModelConfigResponse(
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                config.getEnabled()
        );
    }
}
