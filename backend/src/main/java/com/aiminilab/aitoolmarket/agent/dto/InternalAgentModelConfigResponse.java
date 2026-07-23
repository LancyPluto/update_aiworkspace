package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public record InternalAgentModelConfigResponse(
        Long id,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
        String executionTask,
        String executionOptionsJson,
        String requestSchemaJson,
        String requestMappingJson,
        String responseMappingJson,
        String apiContractVersion,
        String contractStatus,
        java.time.LocalDateTime contractVerifiedAt,
        String minimaxGroupId,
        Integer timeoutSeconds,
        String billingUnit,
        java.math.BigDecimal unitPrice,
        Boolean enabled,
        Boolean agentEnabled,
        List<String> capabilities,
        ProxyPolicy proxyPolicy
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> CAPABILITY_LIST_TYPE = new TypeReference<>() {};

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
                config.getRequestSchemaJson(),
                config.getRequestMappingJson(),
                config.getResponseMappingJson(),
                config.getApiContractVersion(),
                config.getContractStatus(),
                config.getContractVerifiedAt(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled(),
                config.getAgentEnabled(),
                parseCapabilities(config.getCapabilities()),
                proxyPolicy
        );
    }

    private static List<String> parseCapabilities(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, CAPABILITY_LIST_TYPE).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
                    .distinct()
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }
}
