package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;

import java.util.List;

public record ExecutionModelConfigResponse(
        Long id,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
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
                config.getExtraAuthJson(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                capabilities == null ? List.of() : capabilities
        );
    }

    public static ExecutionModelConfigResponse from(ModelExecutionSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new ExecutionModelConfigResponse(
                snapshot.id(),
                snapshot.displayName(),
                snapshot.configCode(),
                snapshot.provider(),
                snapshot.modelName(),
                snapshot.baseUrl(),
                snapshot.apiKey(),
                snapshot.extraAuthJson(),
                snapshot.minimaxGroupId(),
                snapshot.timeoutSeconds(),
                snapshot.capabilities() == null ? List.of() : snapshot.capabilities()
        );
    }
}
