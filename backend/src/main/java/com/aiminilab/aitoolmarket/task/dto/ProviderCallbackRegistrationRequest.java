package com.aiminilab.aitoolmarket.task.dto;

import jakarta.validation.constraints.NotBlank;

public record ProviderCallbackRegistrationRequest(
        @NotBlank String providerCode,
        String claimToken
) {
}
