package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMetricsTest {

    @Test
    void recordsTaskAndQueueFailureMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskMetrics metrics = new TaskMetrics(registry);
        LocalDateTime createdAt = LocalDateTime.now().minusSeconds(3);
        LocalDateTime finishedAt = LocalDateTime.now();

        metrics.recordTaskOutcome("xiaohongshu_copywriting", "SUCCESS", createdAt, finishedAt);
        metrics.recordQueuePublishFailure("TASK_CREATED");

        assertThat(registry.get("ai_task_total").tags("tool_code", "xiaohongshu_copywriting", "status", "SUCCESS").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ai_task_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("ai_task_queue_publish_failed_total").tags("event_type", "TASK_CREATED").counter().count()).isEqualTo(1.0d);
    }
}
