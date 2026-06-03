package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpsertModelVendorRequest(
        @NotBlank String vendorCode,
        @NotBlank String vendorLabel,
        @NotBlank String iconAsset,
        Integer sortOrder,
        @NotNull Boolean enabled
) {
}

