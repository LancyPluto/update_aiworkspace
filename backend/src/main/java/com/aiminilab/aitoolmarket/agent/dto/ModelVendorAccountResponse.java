package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ModelVendorAccountResponse(
        Long id,
        String vendorCode,
        String vendorLabel,
        String accountName,
        String baseUrl,
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
        Boolean enabled,
        int modelCount,
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
                account.getEnabled(),
                modelCount,
                account.getCreatedAt(),
                account.getUpdatedAt()
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
}
