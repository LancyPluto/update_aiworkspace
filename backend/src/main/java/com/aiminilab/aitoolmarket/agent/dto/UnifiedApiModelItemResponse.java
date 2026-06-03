package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;

import java.math.BigDecimal;
import java.util.List;

public record UnifiedApiModelItemResponse(
        Long id,
        Long vendorAccountId,
        String vendorAccountName,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String minimaxGroupId,
        String consoleUrl,
        String balanceUrl,
        String docsUrl,
        Integer timeoutSeconds,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        List<String> capabilities,
        String billingUnit,
        BigDecimal unitPrice,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        Boolean enabled,
        Boolean agentEnabled,
        Boolean isDefault,
        String healthStatus
) {
    public static UnifiedApiModelItemResponse from(AgentModelConfig config,
                                                   String vendorAccountName,
                                                   ModelCapabilitiesCodec codec) {
        return new UnifiedApiModelItemResponse(
                config.getId(),
                config.getVendorAccountId(),
                vendorAccountName,
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getMinimaxGroupId(),
                config.getConsoleUrl(),
                config.getBalanceUrl(),
                config.getDocsUrl(),
                config.getTimeoutSeconds(),
                AgentModelConfigResponse.from(config, codec).connectTimeoutSeconds(),
                AgentModelConfigResponse.from(config, codec).readTimeoutSeconds(),
                codec.parse(config.getCapabilities()),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getEnabled(),
                config.getAgentEnabled(),
                config.getDefault(),
                Boolean.FALSE.equals(config.getEnabled()) ? "DISABLED" : "OK"
        );
    }
}
