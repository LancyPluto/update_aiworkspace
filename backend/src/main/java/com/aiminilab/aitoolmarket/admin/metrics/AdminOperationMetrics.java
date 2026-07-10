package com.aiminilab.aitoolmarket.admin.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class AdminOperationMetrics {
    private final MeterRegistry meterRegistry;

    public AdminOperationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String method, String resource, int status, long durationNanos) {
        String result = status >= 200 && status < 400 ? "success" : "failed";
        String normalizedMethod = normalize(method, "UNKNOWN", 16).toUpperCase(Locale.ROOT);
        String normalizedResource = normalize(resource, "unknown", 64);
        meterRegistry.counter(
                "admin_operation_total",
                "method", normalizedMethod,
                "resource", normalizedResource,
                "result", result
        ).increment();
        Timer.builder("admin_operation_duration_seconds")
                .tags("method", normalizedMethod, "resource", normalizedResource, "result", result)
                .register(meterRegistry)
                .record(Math.max(durationNanos, 0), TimeUnit.NANOSECONDS);
    }

    private String normalize(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
