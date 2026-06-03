package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AgentModelConfigResponse(
        Long id,
        Long vendorAccountId,
        String vendorAccountName,
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
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        String billingUnit,
        BigDecimal unitPrice,
        Boolean enabled,
        Boolean agentEnabled,
        Boolean isDefault,
        String channelCode,
        String channelLabel,
        String channelIconAsset,
        List<String> capabilities,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentModelConfigResponse from(AgentModelConfig config) {
        return from(config, new ModelCapabilitiesCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public static AgentModelConfigResponse from(AgentModelConfig config, ModelCapabilitiesCodec codec) {
        return from(config, codec, null);
    }

    public static AgentModelConfigResponse from(AgentModelConfig config,
                                                ModelCapabilitiesCodec codec,
                                                String vendorAccountName) {
        return from(config, codec, vendorAccountName, null, null, null);
    }

    public static AgentModelConfigResponse from(AgentModelConfig config,
                                                ModelCapabilitiesCodec codec,
                                                String vendorAccountName,
                                                String channelCode,
                                                String channelLabel,
                                                String channelIconAsset) {
        return new AgentModelConfigResponse(
                config.getId(),
                config.getVendorAccountId(),
                vendorAccountName,
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
                extraAuthInt(config.getExtraAuthJson(), "connectTimeoutSeconds"),
                extraAuthInt(config.getExtraAuthJson(), "readTimeoutSeconds"),
                config.getInputTokenPricePer1k(),
                config.getOutputTokenPricePer1k(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled(),
                config.getAgentEnabled(),
                config.getDefault(),
                channelCode,
                channelLabel,
                channelIconAsset,
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

    private static Integer extraAuthInt(String value, String key) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(value);
            JsonNode field = node.get(key);
            if (field == null || field.isNull()) {
                return null;
            }
            if (field.isInt() || field.isLong()) {
                return field.asInt();
            }
            if (field.isTextual() && !field.asText().isBlank()) {
                return Integer.parseInt(field.asText().trim());
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
