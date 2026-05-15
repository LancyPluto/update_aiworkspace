package com.aiminilab.aitoolmarket.commerce.payment;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MockPayProvider implements PaymentProvider {

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public PaymentRequest createPayment(String orderNo, int amountCents, String subject) {
        return new PaymentRequest("mock://pay/" + orderNo, "mock-" + orderNo, "{}");
    }

    @Override
    public PaymentVerification verifyCallback(String rawPayload, Map<String, String> headers) {
        return new PaymentVerification(true, null, null, 0, "PAY_SUCCESS", rawPayload);
    }

    @Override
    public PaymentCallbackAck callbackAck(boolean success, String message) {
        String body = success ? "{\"code\":\"SUCCESS\",\"message\":\"OK\"}" : "{\"code\":\"FAIL\",\"message\":\"FAIL\"}";
        return new PaymentCallbackAck(success ? 200 : 400, "application/json;charset=UTF-8", body);
    }
}
