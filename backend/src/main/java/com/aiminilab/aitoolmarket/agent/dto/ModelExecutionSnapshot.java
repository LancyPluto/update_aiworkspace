package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import java.math.BigDecimal;
import java.util.List;

public record ModelExecutionSnapshot(
        Long id,
        Long vendorAccountId,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
        String executionTask,
        String executionOptionsJson,
        String minimaxGroupId,
        Integer timeoutSeconds,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        String billingUnit,
        BigDecimal unitPrice,
        List<String> capabilities,
        String providerMetadataVersion
) {
    public static ModelExecutionSnapshot from(AgentModelConfig config, List<String> capabilities, String providerMetadataVersion) {
        if (config == null) {
            return null;
        }
        return new ModelExecutionSnapshot(
                config.getId(),
                config.getVendorAccountId(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getExtraAuthJson(),
                config.getExecutionTask(),
                config.getExecutionOptionsJson(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                config.getInputTokenPricePer1k(),
                config.getOutputTokenPricePer1k(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                capabilities == null ? List.of() : capabilities,
                providerMetadataVersion == null || providerMetadataVersion.isBlank() ? "manifest" : providerMetadataVersion
        );
    }

    public AgentModelConfig toModelConfig() {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(id);
        config.setVendorAccountId(vendorAccountId);
        config.setDisplayName(displayName);
        config.setConfigCode(configCode);
        config.setProvider(provider);
        config.setModelName(modelName);
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setExtraAuthJson(extraAuthJson);
        config.setExecutionTask(executionTask);
        config.setExecutionOptionsJson(executionOptionsJson);
        config.setMinimaxGroupId(minimaxGroupId);
        config.setTimeoutSeconds(timeoutSeconds);
        config.setInputTokenPricePer1k(inputTokenPricePer1k);
        config.setOutputTokenPricePer1k(outputTokenPricePer1k);
        config.setInputTokenPricePer1m(inputTokenPricePer1m);
        config.setOutputTokenPricePer1m(outputTokenPricePer1m);
        config.setBillingUnit(billingUnit);
        config.setUnitPrice(unitPrice);
        config.setEnabled(true);
        config.setAgentEnabled(true);
        return config;
    }
}
