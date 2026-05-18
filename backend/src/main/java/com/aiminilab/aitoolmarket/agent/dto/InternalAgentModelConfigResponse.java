package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

public record InternalAgentModelConfigResponse(
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String minimaxGroupId,
        Integer timeoutSeconds,
        String billingUnit,
        java.math.BigDecimal unitPrice,
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
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled()
        );
    }
}
