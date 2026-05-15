package com.aiminilab.aitoolmarket.commerce.payment;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

final class PaymentProviderSupport {

    private PaymentProviderSupport() {
    }

    static BusinessException notConfigured(String channel) {
        return new BusinessException(ErrorCode.PARAM_ERROR, channel + " payment provider is not enabled or configured");
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static int yuanToCents(String amount) {
        if (!hasText(amount)) {
            return 0;
        }
        return new BigDecimal(amount.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    static Map<String, String> parseForm(String payload) {
        Map<String, String> values = new TreeMap<>();
        if (!hasText(payload)) {
            return values;
        }
        for (String pair : payload.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            values.put(key, value);
        }
        return values;
    }

    static String alipayCanonicalContent(Map<String, String> params) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("sign".equals(entry.getKey()) || "sign_type".equals(entry.getKey())) {
                continue;
            }
            if (content.length() > 0) {
                content.append('&');
            }
            content.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return content.toString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
