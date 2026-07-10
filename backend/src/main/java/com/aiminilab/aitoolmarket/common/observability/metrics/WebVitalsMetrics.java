package com.aiminilab.aitoolmarket.common.observability.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class WebVitalsMetrics {
    private static final Set<String> ALLOWED_NAMES = Set.of("CLS", "FCP", "INP", "LCP", "TTFB");
    private static final Set<String> ALLOWED_RATINGS = Set.of("good", "needs-improvement", "poor", "unknown");
    private static final Pattern ID_SEGMENT = Pattern.compile("^[0-9a-fA-F-]{6,}$|^\\d+$");

    private final MeterRegistry meterRegistry;

    public WebVitalsMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String name, String rating, String page, String navigationType, double value) {
        String normalizedName = normalizeName(name);
        String normalizedRating = normalizeRating(rating);
        String normalizedPage = normalizePage(page);
        String normalizedNavigationType = normalize(navigationType, "unknown", 32);
        meterRegistry.counter(
                "web_vital_total",
                "name", normalizedName,
                "rating", normalizedRating,
                "page", normalizedPage,
                "navigation_type", normalizedNavigationType
        ).increment();
        DistributionSummary.builder("web_vital_value")
                .baseUnit("milliseconds")
                .tags(
                        "name", normalizedName,
                        "rating", normalizedRating,
                        "page", normalizedPage,
                        "navigation_type", normalizedNavigationType
                )
                .register(meterRegistry)
                .record(value);
    }

    public boolean isAllowedName(String name) {
        return ALLOWED_NAMES.contains(normalizeName(name));
    }

    private String normalizeName(String value) {
        return normalize(value, "unknown", 32).toUpperCase();
    }

    private String normalizeRating(String value) {
        String normalized = normalize(value, "unknown", 32).toLowerCase();
        return ALLOWED_RATINGS.contains(normalized) ? normalized : "unknown";
    }

    private String normalizePage(String value) {
        String normalized = normalize(value, "/unknown", 160);
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        String[] parts = normalized.split("/");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            builder.append('/').append(ID_SEGMENT.matcher(part).matches() ? "{id}" : part);
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    private String normalize(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
