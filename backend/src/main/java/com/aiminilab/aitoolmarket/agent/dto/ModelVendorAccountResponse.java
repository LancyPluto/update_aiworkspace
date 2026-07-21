package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ModelVendorAccountResponse(
        Long id,
        String vendorCode,
        String vendorLabel,
        String accountName,
        String baseUrl,
        String endpointPath,
        String apiKey,
        String apiKeyMasked,
        String extraAuthJson,
        String extraAuthJsonMasked,
        String consoleUrl,
        String balanceUrl,
        String consoleCookieMasked,
        String consoleCookieStatus,
        String balanceQueryMode,
        BigDecimal balanceAmount,
        String balanceCurrency,
        String balanceStatus,
        BigDecimal balanceLowThreshold,
        LocalDateTime balanceUpdatedAt,
        String balanceErrorMessage,
        String healthStatus,
        String healthMessage,
        LocalDateTime healthCheckedAt,
        Boolean loadBalanceEnabled,
        Integer loadBalanceWeight,
        Long routingPoolId,
        String routingPoolName,
        Integer inFlightCount,
        String circuitState,
        LocalDateTime circuitOpenUntil,
        String routingExclusionReason,
        Boolean enabled,
        int modelCount,
        String proxyMode,
        String proxyUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ModelVendorAccountResponse from(ModelVendorAccount account, String vendorLabel, int modelCount) {
        return new ModelVendorAccountResponse(
                account.getId(),
                account.getVendorCode(),
                vendorLabel,
                account.getAccountName(),
                account.getBaseUrl(),
                extraAuthText(account.getExtraAuthJson(), "endpointPath"),
                null,
                mask(account.getApiKey()),
                null,
                maskJson(account.getExtraAuthJson()),
                account.getConsoleUrl(),
                account.getBalanceUrl(),
                maskCookie(account.getConsoleCookie()),
                account.getConsoleCookieStatus(),
                account.getBalanceQueryMode(),
                account.getBalanceAmount(),
                account.getBalanceCurrency(),
                account.getBalanceStatus(),
                account.getBalanceLowThreshold(),
                account.getBalanceUpdatedAt(),
                account.getBalanceErrorMessage(),
                account.getHealthStatus(),
                account.getHealthMessage(),
                account.getHealthCheckedAt(),
                Boolean.TRUE.equals(account.getLoadBalanceEnabled()),
                normalizeWeight(account.getLoadBalanceWeight()),
                account.getRoutingPoolId(),
                account.getRoutingPoolName(),
                account.getRoutingInFlightCount() == null ? 0 : account.getRoutingInFlightCount(),
                account.getRoutingCircuitStatus() == null ? "CLOSED" : account.getRoutingCircuitStatus(),
                account.getRoutingCooldownUntil(),
                routingExclusionReason(account),
                account.getEnabled(),
                modelCount,
                extraAuthText(account.getExtraAuthJson(), "proxyMode"),
                extraAuthText(account.getExtraAuthJson(), "proxyUrl"),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    private static int normalizeWeight(Integer value) {
        return value == null ? 100 : Math.max(1, Math.min(100, value));
    }

    private static String routingExclusionReason(ModelVendorAccount account) {
        if (!Boolean.TRUE.equals(account.getLoadBalanceEnabled())) {
            return null;
        }
        if (!Boolean.TRUE.equals(account.getEnabled())) {
            return "ACCOUNT_DISABLED";
        }
        if (account.getRoutingPoolId() == null) {
            return "ROUTING_POOL_REQUIRED";
        }
        if ("OPEN".equalsIgnoreCase(account.getRoutingCircuitStatus())) {
            return "CIRCUIT_OPEN";
        }
        return null;
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

    private static String maskCookie(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "***" + value.substring(value.length() - 4);
    }

    private static String maskJson(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "********";
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
