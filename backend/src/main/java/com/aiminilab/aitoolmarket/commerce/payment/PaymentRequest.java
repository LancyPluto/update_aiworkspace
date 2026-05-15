package com.aiminilab.aitoolmarket.commerce.payment;

public record PaymentRequest(
        String paymentUrl,
        String providerTradeNo,
        String rawPayload
) {
}
