package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.URI;

final class VendorBalanceHttpSupport {

    private static final RestClient REST_CLIENT = RestClient.builder().build();

    private VendorBalanceHttpSupport() {
    }

    static String requireApiKey(ModelVendorAccount account) {
        String apiKey = account.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 未配置");
        }
        return apiKey.trim();
    }

    static String getJson(String url, String apiKey) {
        return REST_CLIENT.get()
                .uri(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    static BalanceQueryResult httpFailure(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        String detail = body == null || body.isBlank() ? exception.getMessage() : body;
        if (detail != null && detail.length() > 240) {
            detail = detail.substring(0, 240);
        }
        return BalanceQueryResult.failed("HTTP " + exception.getStatusCode().value() + ": " + detail);
    }

    static BalanceQueryResult httpFailure(Exception exception) {
        String message = exception.getMessage();
        if (message != null && message.length() > 240) {
            message = message.substring(0, 240);
        }
        return BalanceQueryResult.failed(message == null ? "余额查询失败" : message);
    }

    static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new BigDecimal(raw.trim());
    }

    static String normalizeApiOrigin(String baseUrl, String defaultOrigin) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return defaultOrigin;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }
}
