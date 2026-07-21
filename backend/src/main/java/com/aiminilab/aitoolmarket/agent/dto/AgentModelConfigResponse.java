package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import com.aiminilab.aitoolmarket.agent.support.AgentVisionInputSupport;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelRoutePreviewResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AgentModelConfigResponse(
        Long id,
        Long vendorAccountId,
        String vendorAccountName,
        Long routingPoolId,
        String routingPoolName,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String endpointPath,
        String apiKeyMasked,
        String extraAuthJsonMasked,
        String executionTask,
        String executionOptionsJsonMasked,
        ModelRoutePreviewResolver.RoutePreview routePreview,
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
        Boolean chatSelectable,
        String providerMetadataVersion,
        String pricingPreview,
        String effectiveCredentialsStatus,
        String proxyMode,
        String proxyUrl,
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
        return from(config, codec, vendorAccountName, null, null, null, true);
    }

    public static AgentModelConfigResponse from(AgentModelConfig config,
                                                ModelCapabilitiesCodec codec,
                                                String vendorAccountName,
                                                String channelCode,
                                                String channelLabel,
                                                String channelIconAsset) {
        return from(config, codec, vendorAccountName, channelCode, channelLabel, channelIconAsset, true);
    }

    public static AgentModelConfigResponse from(AgentModelConfig config,
                                                ModelCapabilitiesCodec codec,
                                                String vendorAccountName,
                                                String channelCode,
                                                String channelLabel,
                                                String channelIconAsset,
                                                boolean chatSelectable) {
        return from(config, codec, vendorAccountName, channelCode, channelLabel, channelIconAsset, chatSelectable, "manifest");
    }

    public static AgentModelConfigResponse from(AgentModelConfig config,
                                                ModelCapabilitiesCodec codec,
                                                String vendorAccountName,
                                                String channelCode,
                                                String channelLabel,
                                                String channelIconAsset,
                                                boolean chatSelectable,
                                                String providerMetadataVersion) {
        return from(config, codec, vendorAccountName, channelCode, channelLabel, channelIconAsset, chatSelectable, providerMetadataVersion, null);
    }

    public static AgentModelConfigResponse from(AgentModelConfig config,
                                                ModelCapabilitiesCodec codec,
                                                String vendorAccountName,
                                                String channelCode,
                                                String channelLabel,
                                                String channelIconAsset,
                                                boolean chatSelectable,
                                                String providerMetadataVersion,
                                                ModelRoutePreviewResolver.RoutePreview routePreview) {
        return new AgentModelConfigResponse(
                config.getId(),
                config.getVendorAccountId(),
                vendorAccountName,
                config.getRoutingPoolId(),
                config.getRoutingPoolName(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                extraAuthText(config.getExtraAuthJson(), "endpointPath"),
                mask(config.getApiKey()),
                maskJsonSecret(config.getExtraAuthJson()),
                config.getExecutionTask(),
                maskJsonSecret(config.getExecutionOptionsJson()),
                routePreview,
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
                AgentVisionInputSupport.withInferredVisionInput(
                        config.getProvider(),
                        config.getModelName(),
                        config.getBaseUrl(),
                        codec.parse(config.getCapabilities())
                ),
                chatSelectable,
                providerMetadataVersion == null || providerMetadataVersion.isBlank() ? "manifest" : providerMetadataVersion,
                pricingPreview(config),
                credentialsStatus(config),
                extraAuthText(config.getExtraAuthJson(), "proxyMode"),
                extraAuthText(config.getExtraAuthJson(), "proxyUrl"),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private static String pricingPreview(AgentModelConfig config) {
        String unit = config.getBillingUnit() == null ? "TOKEN_PER_M" : config.getBillingUnit();
        if ("PER_CALL".equalsIgnoreCase(unit)) {
            return "PER_CALL " + (config.getUnitPrice() == null ? "0" : config.getUnitPrice().toPlainString());
        }
        if ("PER_SECOND".equalsIgnoreCase(unit)) {
            return "PER_SECOND " + (config.getUnitPrice() == null ? "0" : config.getUnitPrice().toPlainString()) + "/s";
        }
        if ("IMAGE_TOKEN".equalsIgnoreCase(unit)) {
            return "IMAGE_TOKEN input="
                    + (config.getInputTokenPricePer1m() == null ? "0" : config.getInputTokenPricePer1m().toPlainString())
                    + "/M output="
                    + (config.getOutputTokenPricePer1m() == null ? "0" : config.getOutputTokenPricePer1m().toPlainString())
                    + "/M";
        }
        return "TOKEN_PER_M input="
                + (config.getInputTokenPricePer1m() == null ? "0" : config.getInputTokenPricePer1m().toPlainString())
                + "/M output="
                + (config.getOutputTokenPricePer1m() == null ? "0" : config.getOutputTokenPricePer1m().toPlainString())
                + "/M";
    }

    private static String credentialsStatus(AgentModelConfig config) {
        if (config.getApiKey() != null && !config.getApiKey().isBlank() && !config.getApiKey().trim().startsWith("replace-with-")) {
            return "CONFIGURED";
        }
        if (config.getExtraAuthJson() != null && !config.getExtraAuthJson().isBlank()) {
            return "EXTRA_AUTH_CONFIGURED";
        }
        if (config.getVendorAccountId() != null) {
            return "INHERITED_OR_MISSING";
        }
        return "MISSING";
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

    private static String extraAuthText(String value, String key) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            JsonNode node = new ObjectMapper().readTree(value);
            JsonNode field = node.get(key);
            return field == null || field.isNull() ? "" : field.asText("");
        } catch (Exception ignored) {
            return "";
        }
    }
}
