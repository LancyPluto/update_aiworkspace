package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ModelVendorAccountRequest(
        @NotBlank String vendorCode,
        @NotBlank String accountName,
        String baseUrl,
        String apiKey,
        Boolean clearApiKey,
        String extraAuthJson,
        Boolean clearExtraAuthJson,
        String consoleUrl,
        String balanceUrl,
        String balanceQueryMode,
        BigDecimal balanceAmount,
        String balanceCurrency,
        BigDecimal balanceLowThreshold,
        Boolean enabled,
        String proxyMode,
        String proxyUrl
) {
}
