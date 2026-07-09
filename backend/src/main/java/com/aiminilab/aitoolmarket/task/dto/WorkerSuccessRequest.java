package com.aiminilab.aitoolmarket.task.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkerSuccessRequest(
        @NotBlank String resourceType,
        @NotBlank String contentText,
        Integer promptTokens,
        Integer completionTokens,
        Integer billableUnits,
        String claimToken
) {
}
