package com.aiminilab.aitoolmarket.task.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class TaskMetrics {

    private final MeterRegistry meterRegistry;

    public TaskMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordTaskOutcome(String toolCode, String status, LocalDateTime createdAt, LocalDateTime finishedAt) {
        Tags tags = Tags.of(
                "tool_code", normalize(toolCode, "unknown"),
                "status", normalize(status, "unknown")
        );
        meterRegistry.counter("ai_task_total", tags).increment();
        if (createdAt != null) {
            LocalDateTime endTime = finishedAt == null ? LocalDateTime.now() : finishedAt;
            if (endTime.isBefore(createdAt)) {
                endTime = createdAt;
            }
            Timer.builder("ai_task_duration")
                    .tags(tags)
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(Duration.between(createdAt, endTime));
        }
    }

    public void recordQueuePublishFailure(String eventType) {
        meterRegistry.counter(
                "ai_task_queue_publish_failed_total",
                "event_type",
                normalize(eventType, "unknown")
        ).increment();
    }

    public void recordLeaseClaim(String result, String reason) {
        meterRegistry.counter(
                "ai_task_lease_claim_total",
                "result", normalize(result, "unknown"),
                "reason", normalize(reason, "unknown")
        ).increment();
    }

    public void recordLeaseRenew(String result) {
        meterRegistry.counter(
                "ai_task_lease_renew_total",
                "result", normalize(result, "unknown")
        ).increment();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
