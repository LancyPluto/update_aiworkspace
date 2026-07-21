package com.aiminilab.aitoolmarket.agent.connectivity;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class AccountProbeErrorClassifier {

    private static final List<String> CREDENTIAL_PHRASES = List.of(
            "invalid api key",
            "invalid_api_key",
            "api key is invalid",
            "api key not valid",
            "unauthorized",
            "authentication failed",
            "authentication error",
            "signature invalid",
            "invalid signature",
            "invalid token",
            "鉴权失败",
            "密钥无效",
            "签名无效"
    );

    private static final List<String> BILLING_PHRASES = List.of(
            "insufficient balance",
            "insufficient quota",
            "quota exhausted",
            "free quota",
            "current quota",
            "payment required",
            "billing",
            "余额不足",
            "额度不足",
            "额度已用完",
            "免费额度",
            "欠费"
    );

    public Classification classify(int httpStatus, String body) {
        String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (httpStatus >= 200 && httpStatus < 300) {
            return new Classification(Decision.PASS, "账户网关与凭据有效");
        }
        if (httpStatus == 401) {
            return credentialFailure(httpStatus);
        }
        if (httpStatus == 403) {
            if (containsAny(normalized, BILLING_PHRASES)) {
                return billingWarning(httpStatus);
            }
            return credentialFailure(httpStatus);
        }
        if (httpStatus == 402 || httpStatus == 429) {
            return billingWarning(httpStatus);
        }
        if (httpStatus == 404 || httpStatus == 405 || httpStatus == 501) {
            return new Classification(Decision.FALLBACK, "厂商未实现模型列表接口（HTTP " + httpStatus + "）");
        }
        if (httpStatus >= 500) {
            return new Classification(Decision.FAIL, "账户网关探活失败（HTTP " + httpStatus + "）");
        }
        if (containsAny(normalized, CREDENTIAL_PHRASES)) {
            return credentialFailure(httpStatus);
        }
        return new Classification(Decision.FAIL, "账户网关探活失败（HTTP " + httpStatus + "）");
    }

    private Classification credentialFailure(int httpStatus) {
        return new Classification(Decision.FAIL, "API Key 无效或权限不足（HTTP " + httpStatus + "）");
    }

    private Classification billingWarning(int httpStatus) {
        return new Classification(Decision.WARNING,
                "凭据有效，但上游返回余额、额度或限流告警（HTTP " + httpStatus + "）");
    }

    private boolean containsAny(String body, List<String> phrases) {
        return phrases.stream().anyMatch(body::contains);
    }

    public enum Decision {
        PASS,
        WARNING,
        FAIL,
        FALLBACK
    }

    public record Classification(Decision decision, String message) {
    }
}
