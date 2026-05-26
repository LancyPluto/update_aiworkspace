package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public record AgentModelConfigRequest(
        String displayName,
        String configCode,
        @NotBlank String provider,
        @NotBlank String modelName,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
        String minimaxGroupId,
        String consoleUrl,
        String balanceUrl,
        String docsUrl,
        @Min(1) @Max(300) Integer timeoutSeconds,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        String billingUnit,
        BigDecimal unitPrice,
        Boolean enabled,
        Boolean agentEnabled,
        Boolean isDefault,
        List<String> capabilities
) {
}
