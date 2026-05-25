package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRechargeOrderRequest(
        @NotNull Long packageId,
        @Size(max = 32) String paymentChannel,
        @Size(max = 128) String clientRequestId
) {
}
