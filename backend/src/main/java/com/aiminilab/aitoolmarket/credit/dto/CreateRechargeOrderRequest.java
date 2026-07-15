package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateRechargeOrderRequest(
        Long packageId,
        @Size(max = 32) String paymentChannel,
        @NotBlank @Size(max = 128) String clientRequestId,
        @Size(max = 32) String orderType,
        Long giftCardPackageId,
        Integer quantity,
        List<GiftCardItemRequest> giftCardItems
) {
    public String orderType() {
        String t = orderType;
        return (t == null || t.isBlank()) ? "CREDITS" : t;
    }

    public record GiftCardItemRequest(
            Long giftCardPackageId,
            Integer quantity
    ) {
    }
}
