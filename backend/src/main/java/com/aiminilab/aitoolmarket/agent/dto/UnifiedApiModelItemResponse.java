package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelRoutePreviewResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

public record UnifiedApiModelItemResponse(
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
        String minimaxGroupId,
        String consoleUrl,
        String balanceUrl,
        String docsUrl,
        Integer timeoutSeconds,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        List<String> capabilities,
        String executionTask,
        String executionOptionsJsonMasked,
        ModelRoutePreviewResolver.RoutePreview routePreview,
        String billingUnit,
        BigDecimal unitPrice,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        Boolean enabled,
        Boolean agentEnabled,
        Boolean isDefault,
        String routingExclusionReason,
        String healthStatus
) {
    public static UnifiedApiModelItemResponse from(AgentModelConfig config,
                                                   String vendorAccountName,
                                                   ModelCapabilitiesCodec codec) {
        return from(config, vendorAccountName, codec, null, null);
    }

    public static UnifiedApiModelItemResponse from(AgentModelConfig config,
                                                   String vendorAccountName,
                                                   ModelCapabilitiesCodec codec,
                                                   String accountHealthStatus) {
        return from(config, vendorAccountName, codec, accountHealthStatus, null);
    }

    public static UnifiedApiModelItemResponse from(AgentModelConfig config,
                                                   String vendorAccountName,
                                                   ModelCapabilitiesCodec codec,
                                                   String accountHealthStatus,
                                                   String routingExclusionReason) {
        AgentModelConfigResponse summary = AgentModelConfigResponse.from(config, codec);
        ModelRoutePreviewResolver.RoutePreview routePreview = ROUTE_PREVIEW_RESOLVER.resolve(config);
        return new UnifiedApiModelItemResponse(
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
                summary.endpointPath(),
                config.getMinimaxGroupId(),
                config.getConsoleUrl(),
                config.getBalanceUrl(),
                config.getDocsUrl(),
                config.getTimeoutSeconds(),
                summary.connectTimeoutSeconds(),
                summary.readTimeoutSeconds(),
                codec.parse(config.getCapabilities()),
                config.getExecutionTask(),
                mask(config.getExecutionOptionsJson()),
                routePreview,
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getEnabled(),
                config.getAgentEnabled(),
                config.getDefault(),
                routingExclusionReason,
                resolveModelHealthStatus(config)
        );
    }

    private static final ModelRoutePreviewResolver ROUTE_PREVIEW_RESOLVER = new ModelRoutePreviewResolver(new ObjectMapper());

    private static String mask(String value) {
        return value == null || value.isBlank() ? "" : "********";
    }

    private static String resolveModelHealthStatus(AgentModelConfig config) {
        if (Boolean.FALSE.equals(config.getEnabled())) {
            return "DISABLED";
        }
        if (config.getLastTestSuccess() != null) {
            return Boolean.TRUE.equals(config.getLastTestSuccess()) ? "OK" : "ERROR";
        }
        return "UNKNOWN";
    }
}
