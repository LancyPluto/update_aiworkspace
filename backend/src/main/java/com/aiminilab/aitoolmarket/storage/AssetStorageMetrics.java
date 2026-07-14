package com.aiminilab.aitoolmarket.storage;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AssetStorageMetrics {
    private final MeterRegistry registry;

    public AssetStorageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String visibility, String outcome, long bytes, long nanos) {
        registry.counter("asset_storage_operation_total", "operation", operation,
                "visibility", visibility, "outcome", outcome).increment();
        if (bytes > 0) {
            DistributionSummary.builder("asset_storage_bytes").baseUnit("bytes")
                    .tags("operation", operation, "visibility", visibility).register(registry).record(bytes);
        }
        if (nanos > 0) {
            Timer.builder("asset_storage_operation_duration").tags("operation", operation,
                    "visibility", visibility, "outcome", outcome).register(registry).record(Duration.ofNanos(nanos));
        }
    }
}
