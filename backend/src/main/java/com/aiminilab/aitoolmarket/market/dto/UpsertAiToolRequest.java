package com.aiminilab.aitoolmarket.market.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpsertAiToolRequest(
        @Size(max = 32) String id,
        @NotBlank @Size(max = 20) String name,
        @NotBlank String iconUrl,
        @Size(max = 100) String description,
        @NotNull Boolean enabled,
        @Positive Integer order,
        @Size(max = 16) String primaryColor,
        String welcomeMessage,
        @Valid List<CapabilityDto> capabilities
) {
}
