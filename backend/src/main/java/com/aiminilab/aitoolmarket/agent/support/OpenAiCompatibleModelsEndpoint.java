package com.aiminilab.aitoolmarket.agent.support;

import java.net.URI;

/**
 * Resolves OpenAI-compatible {@code /models} list URLs for connectivity probes.
 * Volcengine Ark uses {@code /api/v3/models}, not {@code /api/v3/v1/models}.
 */
public final class OpenAiCompatibleModelsEndpoint {

    private OpenAiCompatibleModelsEndpoint() {
    }

    public static URI toUri(String baseUrl) {
        return URI.create(resolve(baseUrl));
    }

    public static String resolve(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "/v1/models";
        }
        if (normalized.endsWith("/models")) {
            return normalized;
        }
        if (normalized.equals("https://ark.cn-beijing.volces.com")) {
            return normalized + "/api/v3/models";
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/api/v3")) {
            return normalized + "/models";
        }
        return normalized + "/v1/models";
    }
}
