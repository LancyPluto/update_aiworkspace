package com.aiminilab.aitoolmarket.commerce.payment;

public record PaymentVerification(
        boolean verified,
        String orderNo,
        String providerTradeNo,
        int amountCents,
        String eventType,
        String rawPayload
) {
}
