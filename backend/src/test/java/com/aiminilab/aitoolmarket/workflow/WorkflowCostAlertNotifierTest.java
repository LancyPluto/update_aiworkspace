package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowCostAlertNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCostAlertNotifierTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void dailyLimitAlertIsDeliveredOnceWithoutUserIdentifiers() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workflow-cost", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setCostAlertWebhookUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/workflow-cost"
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowCostAlertNotifier notifier = new WorkflowCostAlertNotifier(
                properties,
                new ObjectMapper(),
                new WorkflowMetrics(registry),
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        assertThat(notifier.notifyLimitReached(
                "provider_daily_cost_limit_exceeded",
                new BigDecimal("100.00"),
                new BigDecimal("100.00")
        )).isTrue();
        assertThat(notifier.notifyLimitReached(
                "provider_daily_cost_limit_exceeded",
                new BigDecimal("101.00"),
                new BigDecimal("100.00")
        )).isTrue();

        assertThat(requests).hasValue(1);
        JsonNode payload = new ObjectMapper().readTree(body.get());
        assertThat(payload.path("eventType").asText()).isEqualTo("WORKFLOW_PROVIDER_COST_GATE_BLOCKED");
        assertThat(payload.path("reason").asText()).isEqualTo("provider_daily_cost_limit_exceeded");
        assertThat(payload.path("providerCostCny").asText()).isEqualTo("100.00");
        assertThat(payload.path("limitCny").asText()).isEqualTo("100.00");
        assertThat(body.get()).doesNotContain("userId", "runId", "taskId");
        assertThat(registry.counter("workflow_cost_alert_total", "result", "delivered").count())
                .isEqualTo(1);
        assertThat(registry.counter("workflow_cost_alert_total", "result", "suppressed").count())
                .isEqualTo(1);
    }

    @Test
    void failedReceiverIsThrottledAndRemainsRetryable() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workflow-cost", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setCostAlertWebhookUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/workflow-cost"
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowCostAlertNotifier notifier = new WorkflowCostAlertNotifier(
                properties,
                new ObjectMapper(),
                new WorkflowMetrics(registry),
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        assertThat(notifier.notifyLimitReached(
                "provider_daily_cost_limit_exceeded",
                new BigDecimal("100.00"),
                new BigDecimal("100.00")
        )).isFalse();
        assertThat(notifier.notifyLimitReached(
                "provider_daily_cost_limit_exceeded",
                new BigDecimal("100.00"),
                new BigDecimal("100.00")
        )).isFalse();

        assertThat(requests).hasValue(1);
        assertThat(registry.counter("workflow_cost_alert_total", "result", "failed").count())
                .isEqualTo(1);
        assertThat(registry.counter("workflow_cost_alert_total", "result", "suppressed").count())
                .isEqualTo(1);
    }
}
