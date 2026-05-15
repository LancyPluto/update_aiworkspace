package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    @Test
    void recordsRunAndToolMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(2);
        LocalDateTime finishedAt = LocalDateTime.now();

        metrics.recordRunOutcome("SUCCESS", "tool_use", startedAt, finishedAt);
        metrics.recordToolCallOutcome("xiaohongshu_copywriting", "FAILED", startedAt, finishedAt);

        assertThat(registry.get("ai_agent_run_total").tags("status", "SUCCESS", "intent", "tool_use").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ai_agent_tool_call_total").tags("tool_code", "xiaohongshu_copywriting", "status", "FAILED").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ai_agent_run_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("ai_agent_tool_call_duration").timer().count()).isEqualTo(1L);
    }
}
