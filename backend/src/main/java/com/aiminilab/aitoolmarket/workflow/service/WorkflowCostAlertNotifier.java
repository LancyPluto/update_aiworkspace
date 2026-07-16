package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WorkflowCostAlertNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowCostAlertNotifier.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FAILURE_RETRY_DELAY = Duration.ofMinutes(1);

    private final WorkflowRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final WorkflowMetrics metrics;
    private final HttpClient httpClient;
    private final Clock clock;
    private LocalDate deliveredDay;
    private Instant retryAfter = Instant.EPOCH;

    @Autowired
    public WorkflowCostAlertNotifier(WorkflowRuntimeProperties properties,
                                     ObjectMapper objectMapper,
                                     WorkflowMetrics metrics) {
        this(
                properties,
                objectMapper,
                metrics,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                Clock.system(SHANGHAI)
        );
    }

    public WorkflowCostAlertNotifier(WorkflowRuntimeProperties properties,
                                     ObjectMapper objectMapper,
                                     WorkflowMetrics metrics,
                                     HttpClient httpClient,
                                     Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public boolean notifyLimitReached(String reason, BigDecimal providerCostCny) {
        return notifyLimitReached(reason, providerCostCny, properties.getMaxProviderDailyCostCny());
    }

    @Async
    public void notifyLimitReachedAsync(String reason, BigDecimal providerCostCny) {
        notifyLimitReached(reason, providerCostCny);
    }

    public synchronized boolean notifyLimitReached(String reason,
                                                   BigDecimal providerCostCny,
                                                   BigDecimal limitCny) {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (today.equals(deliveredDay)) {
            metrics.recordCostAlert(WorkflowMetrics.CostAlertResult.SUPPRESSED);
            return true;
        }
        Instant now = clock.instant();
        if (now.isBefore(retryAfter)) {
            metrics.recordCostAlert(WorkflowMetrics.CostAlertResult.SUPPRESSED);
            return false;
        }

        String webhook = properties.getCostAlertWebhookUrl();
        if (webhook == null || webhook.isBlank()) {
            recordFailure(now, "Workflow cost alert webhook is not configured", null);
            return false;
        }
        try {
            URI uri = URI.create(webhook);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                recordFailure(now, "Workflow cost alert webhook scheme is invalid", null);
                return false;
            }
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("eventType", "WORKFLOW_PROVIDER_COST_GATE_BLOCKED");
            payload.put("reason", normalizeReason(reason));
            payload.put("providerCostCny", decimal(providerCostCny));
            payload.put("limitCny", decimal(limitCny));
            payload.put("occurredAt", now.toString());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<Void> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                recordFailure(now, "Workflow cost alert receiver returned HTTP " + response.statusCode(), null);
                return false;
            }
            deliveredDay = today;
            retryAfter = Instant.EPOCH;
            metrics.recordCostAlert(WorkflowMetrics.CostAlertResult.DELIVERED);
            return true;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure(now, "Workflow cost alert delivery failed", exception);
            return false;
        }
    }

    private void recordFailure(Instant now, String message, Exception exception) {
        retryAfter = now.plus(FAILURE_RETRY_DELAY);
        metrics.recordCostAlert(WorkflowMetrics.CostAlertResult.FAILED);
        if (exception == null) {
            LOGGER.error(message);
        } else {
            LOGGER.error(message, exception);
        }
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "provider_cost_gate_blocked" : reason.trim();
    }

    private String decimal(BigDecimal value) {
        return value == null ? "unknown" : value.toPlainString();
    }
}
