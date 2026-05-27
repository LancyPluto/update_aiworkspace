package com.aiminilab.aitoolmarket.market.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String toolId
) {
}
