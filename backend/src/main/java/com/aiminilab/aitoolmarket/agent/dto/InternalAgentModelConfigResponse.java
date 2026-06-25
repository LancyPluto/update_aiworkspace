package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

public record InternalAgentModelConfigResponse(
        Long id,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
        String executionTask,
        String executionOptionsJson,
        String minimaxGroupId,
        Integer timeoutSeconds,
        String billingUnit,
        java.math.BigDecimal unitPrice,
        Boolean enabled,
        Boolean agentEnabled,
        ProxyPolicy proxyPolicy
) {
    public static InternalAgentModelConfigResponse from(AgentModelConfig config) {
        return from(config, null);
    }

    public static InternalAgentModelConfigResponse from(AgentModelConfig config, ProxyPolicy proxyPolicy) {
        return new InternalAgentModelConfigResponse(
                config.getId(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getExtraAuthJson(),
                config.getExecutionTask(),
                config.getExecutionOptionsJson(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled(),
                config.getAgentEnabled(),
                proxyPolicy
        );
    }
}
