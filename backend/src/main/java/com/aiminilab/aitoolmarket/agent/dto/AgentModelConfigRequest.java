package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AgentModelConfigRequest(
        @NotBlank String provider,
        @NotBlank String modelName,
        String baseUrl,
        String apiKey,
        String minimaxGroupId,
        @Min(1) @Max(300) Integer timeoutSeconds,
        Boolean enabled
) {
}
