package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.Size;

public record CreateRechargeOrderRequest(
        Long packageId,
        @Size(max = 32) String paymentChannel,
        @Size(max = 128) String clientRequestId,
        @Size(max = 32) String orderType,
        Long giftCardPackageId
) {
    public String orderType() {
        String t = orderType;
        return (t == null || t.isBlank()) ? "CREDITS" : t;
    }
}
