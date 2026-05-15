package com.aiminilab.aitoolmarket.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentOrderRequest(
        @NotBlank String productType,
        @NotNull Long productId,
        @NotBlank String channel
) {
}
