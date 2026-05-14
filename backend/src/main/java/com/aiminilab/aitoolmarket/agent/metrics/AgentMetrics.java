package com.aiminilab.aitoolmarket.agent.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRunOutcome(String status, String intent, LocalDateTime startedAt, LocalDateTime createdAt) {
        Tags tags = Tags.of(
                "status", normalize(status, "unknown"),
                "intent", normalize(intent, "unknown")
        );
        meterRegistry.counter("ai_agent_run_total", tags).increment();
        recordDuration("ai_agent_run_duration", tags, startedAt, createdAt);
    }

    public void recordToolCallOutcome(String toolCode, String status, LocalDateTime startedAt, LocalDateTime finishedAt) {
        Tags tags = Tags.of(
                "tool_code", normalize(toolCode, "unknown"),
                "status", normalize(status, "unknown")
        );
        meterRegistry.counter("ai_agent_tool_call_total", tags).increment();
        recordDuration("ai_agent_tool_call_duration", tags, startedAt, finishedAt);
    }

    private void recordDuration(String metricName, Tags tags, LocalDateTime startedAt, LocalDateTime fallbackEndTime) {
        if (startedAt == null) {
            return;
        }
        LocalDateTime endTime = fallbackEndTime == null ? LocalDateTime.now() : fallbackEndTime;
        if (endTime.isBefore(startedAt)) {
            endTime = startedAt;
        }
        Timer.builder(metricName)
                .tags(tags)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.between(startedAt, endTime));
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
