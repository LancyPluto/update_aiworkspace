package com.aiminilab.aitoolmarket.commerce.payment;

import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AlipayProvider implements PaymentProvider {

    private final AppProperties.Payment.Alipay config;

    public AlipayProvider(AppProperties appProperties) {
        this.config = appProperties.getPayment().getAlipay();
    }

    @Override
    public String channel() {
        return "ALIPAY";
    }

    @Override
    public PaymentRequest createPayment(String orderNo, int amountCents, String subject) {
        if (!isConfigured()) {
            throw PaymentProviderSupport.notConfigured("Alipay");
        }
        throw PaymentProviderSupport.notConfigured("Alipay order creation");
    }

    @Override
    public PaymentVerification verifyCallback(String rawPayload, Map<String, String> headers) {
        if (!isConfigured()) {
            return unverified(rawPayload);
        }
        Map<String, String> params = PaymentProviderSupport.parseForm(rawPayload);
        String sign = params.get("sign");
        String canonicalContent = PaymentProviderSupport.alipayCanonicalContent(params);
        if (!PaymentProviderSupport.hasText(sign) || !verifyAlipaySignature(canonicalContent, sign)) {
            return unverified(rawPayload);
        }
        try {
            String tradeStatus = params.get("trade_status");
            boolean paid = "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
            return new PaymentVerification(paid, params.get("out_trade_no"), params.get("trade_no"),
                    PaymentProviderSupport.yuanToCents(params.get("total_amount")), value(tradeStatus, "PAY_SUCCESS"),
                    rawPayload);
        } catch (RuntimeException ignored) {
            return unverified(rawPayload);
        }
    }

    @Override
    public PaymentCallbackAck callbackAck(boolean success, String message) {
        return new PaymentCallbackAck(200, "text/plain;charset=UTF-8", success ? "success" : "failure");
    }

    private boolean isConfigured() {
        return config.isEnabled()
                && PaymentProviderSupport.hasText(config.getAppId())
                && PaymentProviderSupport.hasText(config.getMerchantPrivateKey())
                && PaymentProviderSupport.hasText(config.getAlipayPublicKey());
    }

    private boolean verifyAlipaySignature(String canonicalContent, String sign) {
        // Placeholder boundary: keep sorted form content ready for Alipay RSA2 verification.
        return false;
    }

    private static PaymentVerification unverified(String rawPayload) {
        return new PaymentVerification(false, null, null, 0, "VERIFY_FAILED", rawPayload);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
