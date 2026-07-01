package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateGiftCardOrderRequest(
        @NotNull Long giftCardPackageId,
        @Size(max = 32) String paymentChannel,
        @Size(max = 128) String clientRequestId
) {
}
