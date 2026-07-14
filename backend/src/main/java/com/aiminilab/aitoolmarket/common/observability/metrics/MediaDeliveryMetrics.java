package com.aiminilab.aitoolmarket.common.observability.metrics;

import com.aiminilab.aitoolmarket.common.observability.dto.MediaDeliveryEventRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MediaDeliveryMetrics {
    private static final Logger log = LoggerFactory.getLogger(MediaDeliveryMetrics.class);
    private final MeterRegistry registry;

    public MediaDeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordClientEvent(MediaDeliveryEventRequest event) {
        try {
            registry.counter("media_client_event_total",
                    "page", normalizePage(event.getPage()),
                    "kind", event.getMediaKind(),
                    "stage", event.getStage(),
                    "outcome", event.getOutcome())
                    .increment(event.getCount());
        } catch (RuntimeException exception) {
            log.debug("Media delivery metric recording failed", exception);
        }
    }

    private String normalizePage(String page) {
        if (page == null) return "/other";
        return switch (page) {
            case "/home", "/dashboard", "/community", "/library", "/tools" -> page;
            default -> "/other";
        };
    }
}
