package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AgentModelConfigResponse(
        Long id,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKeyMasked,
        String extraAuthJsonMasked,
        String minimaxGroupId,
        String consoleUrl,
        String balanceUrl,
        String docsUrl,
        Integer timeoutSeconds,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        String billingUnit,
        BigDecimal unitPrice,
        Boolean enabled,
        Boolean agentEnabled,
        Boolean isDefault,
        List<String> capabilities,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentModelConfigResponse from(AgentModelConfig config) {
        return from(config, new ModelCapabilitiesCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public static AgentModelConfigResponse from(AgentModelConfig config, ModelCapabilitiesCodec codec) {
        return new AgentModelConfigResponse(
                config.getId(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                mask(config.getApiKey()),
                maskJsonSecret(config.getExtraAuthJson()),
                config.getMinimaxGroupId(),
                config.getConsoleUrl(),
                config.getBalanceUrl(),
                config.getDocsUrl(),
                config.getTimeoutSeconds(),
                config.getInputTokenPricePer1k(),
                config.getOutputTokenPricePer1k(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled(),
                config.getAgentEnabled(),
                config.getDefault(),
                codec.parse(config.getCapabilities()),
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

    private static String maskJsonSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "********";
    }
}
