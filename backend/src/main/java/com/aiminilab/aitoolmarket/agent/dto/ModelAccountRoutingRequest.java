package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ModelAccountRoutingRequest(
        @NotNull Boolean loadBalanceEnabled,
        @NotNull @Min(1) @Max(100) Integer loadBalanceWeight
) {
}
