package com.aiminilab.aitoolmarket.commerce.payment;

import java.util.Map;

public interface PaymentProvider {

    String channel();

    PaymentRequest createPayment(String orderNo, int amountCents, String subject);

    PaymentVerification verifyCallback(String rawPayload, Map<String, String> headers);

    PaymentCallbackAck callbackAck(boolean success, String message);
}
