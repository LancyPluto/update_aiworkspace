package com.aiminilab.aitoolmarket.auth.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AuthMetrics {

    private final MeterRegistry meterRegistry;

    public AuthMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordLoginAttempt(String result, String method, String userType, String reason) {
        meterRegistry.counter(
                "auth_login_attempts_total",
                "result", normalize(result, "unknown"),
                "method", normalize(method, "unknown"),
                "user_type", normalize(userType, "unknown"),
                "reason", normalize(reason, "none")
        ).increment();
    }

    public void recordUnauthorizedRequest(String endpoint, String reason) {
        meterRegistry.counter(
                "auth_request_unauthorized_total",
                "endpoint", normalizeEndpoint(endpoint),
                "reason", normalize(reason, "unknown")
        ).increment();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "unknown";
        }
        return endpoint
                .replaceAll("/\\d+", "/{id}")
                .replaceAll("/[0-9a-fA-F]{24,}", "/{id}");
    }
}
