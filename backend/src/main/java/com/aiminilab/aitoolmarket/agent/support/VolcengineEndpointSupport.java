package com.aiminilab.aitoolmarket.agent.support;

import java.net.URI;
import java.util.Locale;

public final class VolcengineEndpointSupport {

    private VolcengineEndpointSupport() {
    }

    public static String normalizeProviderBaseUrl(String provider, String baseUrl) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String normalizedBaseUrl = trimTrailingSlash(baseUrl);
        if (normalizedBaseUrl.isBlank() || !isVolcengineArkBaseUrl(normalizedBaseUrl)) {
            return normalizedBaseUrl;
        }
        if (!normalizedProvider.equals("openai_compatible")
                && !normalizedProvider.equals("seedance")
                && !normalizedProvider.equals("volcengine_images")) {
            return normalizedBaseUrl;
        }
        if (normalizedBaseUrl.endsWith("/api/v3")) {
            return normalizedBaseUrl;
        }
        if (normalizedBaseUrl.endsWith("/v1")) {
            return normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 3) + "/api/v3";
        }
        return normalizedBaseUrl + "/api/v3";
    }

    public static boolean isVolcengineArkBaseUrl(String baseUrl) {
        String host;
        try {
            host = URI.create(defaultString(baseUrl)).getHost();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        String normalizedHost = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return normalizedHost.endsWith("volces.com") || normalizedHost.endsWith("volcengine.com");
    }

    private static String trimTrailingSlash(String value) {
        String normalized = defaultString(value).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
