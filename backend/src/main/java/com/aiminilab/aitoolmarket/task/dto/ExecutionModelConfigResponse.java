package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import java.util.List;

public record ExecutionModelConfigResponse(
        Long id,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String minimaxGroupId,
        Integer timeoutSeconds,
        List<String> capabilities
) {
    public static ExecutionModelConfigResponse from(AgentModelConfig config, List<String> capabilities) {
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
                config.getTimeoutSeconds(),
                capabilities == null ? List.of() : capabilities
        );
    }
}
