package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record AgentModelConfigRequest(
        String displayName,
        String configCode,
        @NotBlank String provider,
        @NotBlank String modelName,
        String baseUrl,
        String apiKey,
        String minimaxGroupId,
        @Min(1) @Max(300) Integer timeoutSeconds,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        Boolean enabled,
        Boolean isDefault
) {
}
