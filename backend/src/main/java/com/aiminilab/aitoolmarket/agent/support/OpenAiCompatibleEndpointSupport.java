package com.aiminilab.aitoolmarket.agent.support;

import java.util.List;
import java.util.Locale;

/**
 * Normalizes OpenAI-compatible gateway URLs entered as either a root, a versioned root,
 * or a full capability endpoint.
 */
public final class OpenAiCompatibleEndpointSupport {

    private static final List<String> KNOWN_ENDPOINT_SUFFIXES = List.of(
            "/chat/completions",
            "/images/generations",
            "/images/edits",
            "/models"
    );

    private OpenAiCompatibleEndpointSupport() {
    }

    public static NormalizedEndpoint normalize(String rawUrl) {
        String normalized = trimTrailingSlash(rawUrl);
        if (normalized.isBlank()) {
            return new NormalizedEndpoint("", null);
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        for (String suffix : KNOWN_ENDPOINT_SUFFIXES) {
            if (lowered.endsWith(suffix)) {
                String baseUrl = normalized.substring(0, normalized.length() - suffix.length());
                String endpointPath = "/models".equals(suffix) ? null : suffix;
                return new NormalizedEndpoint(trimTrailingSlash(baseUrl), endpointPath);
            }
        }
        return new NormalizedEndpoint(normalized, null);
    }

    public static String normalizedBaseUrl(String rawUrl) {
        return normalize(rawUrl).baseUrl();
    }

    public static String normalizedEndpointPath(String rawUrl) {
        return normalize(rawUrl).endpointPath();
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record NormalizedEndpoint(String baseUrl, String endpointPath) {
        public boolean hasEndpointPath() {
            return endpointPath != null && !endpointPath.isBlank();
        }
    }
}
