package com.aiminilab.aitoolmarket.commerce.payment;

public record PaymentCallbackAck(
        int httpStatus,
        String contentType,
        String body
) {
}
