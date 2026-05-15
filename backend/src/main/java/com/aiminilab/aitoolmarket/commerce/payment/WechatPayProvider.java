package com.aiminilab.aitoolmarket.commerce.payment;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WechatPayProvider implements PaymentProvider {

    private final AppProperties.Payment.Wechat config;
    private final ObjectMapper objectMapper;

    public WechatPayProvider(AppProperties appProperties, ObjectMapper objectMapper) {
        this.config = appProperties.getPayment().getWechat();
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "WECHAT";
    }

    @Override
    public PaymentRequest createPayment(String orderNo, int amountCents, String subject) {
        if (!isConfigured()) {
            throw PaymentProviderSupport.notConfigured("Wechat Pay");
        }
        throw PaymentProviderSupport.notConfigured("Wechat Pay native order creation");
    }

    @Override
    public PaymentVerification verifyCallback(String rawPayload, Map<String, String> headers) {
        if (!isConfigured()) {
            return unverified(rawPayload);
        }
        String timestamp = PaymentProviderSupport.header(headers, "Wechatpay-Timestamp");
        String nonce = PaymentProviderSupport.header(headers, "Wechatpay-Nonce");
        String serial = PaymentProviderSupport.header(headers, "Wechatpay-Serial");
        String signature = PaymentProviderSupport.header(headers, "Wechatpay-Signature");
        if (!PaymentProviderSupport.hasText(timestamp)
                || !PaymentProviderSupport.hasText(nonce)
                || !PaymentProviderSupport.hasText(serial)
                || !PaymentProviderSupport.hasText(signature)) {
            return unverified(rawPayload);
        }

        String canonicalMessage = timestamp + "\n" + nonce + "\n" + rawPayload + "\n";
        if (!verifyWechatSignature(canonicalMessage, signature, serial)) {
            return unverified(rawPayload);
        }
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode resource = root.path("resource");
            JsonNode payload = resource.hasNonNull("ciphertext") ? objectMapper.createObjectNode() : root;
            String orderNo = text(payload, "out_trade_no");
            String tradeNo = text(payload, "transaction_id");
            String tradeState = text(payload, "trade_state");
            int amountCents = payload.path("amount").path("total").asInt(0);
            return new PaymentVerification("SUCCESS".equals(tradeState), orderNo, tradeNo, amountCents,
                    value(tradeState, "PAY_SUCCESS"), rawPayload);
        } catch (Exception ignored) {
            return unverified(rawPayload);
        }
    }

    @Override
    public PaymentCallbackAck callbackAck(boolean success, String message) {
        if (success) {
            return new PaymentCallbackAck(200, "application/json;charset=UTF-8",
                    "{\"code\":\"SUCCESS\",\"message\":\"成功\"}");
        }
        return new PaymentCallbackAck(500, "application/json;charset=UTF-8",
                "{\"code\":\"FAIL\",\"message\":\"" + escapeJson(value(message, "callback failed")) + "\"}");
    }

    private boolean isConfigured() {
        return config.isEnabled()
                && PaymentProviderSupport.hasText(config.getAppId())
                && PaymentProviderSupport.hasText(config.getMchId())
                && PaymentProviderSupport.hasText(config.getApiV3Key())
                && PaymentProviderSupport.hasText(config.getMerchantSerialNo())
                && PaymentProviderSupport.hasText(config.getPlatformCertificatePath());
    }

    private boolean verifyWechatSignature(String canonicalMessage, String signature, String serial) {
        // Placeholder boundary: keep the official canonical input ready for RSA verification.
        return false;
    }

    private static PaymentVerification unverified(String rawPayload) {
        return new PaymentVerification(false, null, null, 0, "VERIFY_FAILED", rawPayload);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
