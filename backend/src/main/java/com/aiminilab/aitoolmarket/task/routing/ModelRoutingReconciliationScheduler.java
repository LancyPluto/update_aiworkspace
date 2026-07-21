package com.aiminilab.aitoolmarket.task.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ModelRoutingReconciliationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelRoutingReconciliationScheduler.class);

    private final ModelRoutingService routingService;

    public ModelRoutingReconciliationScheduler(ModelRoutingService routingService) {
        this.routingService = routingService;
    }

    @Scheduled(fixedDelayString = "${app.model-routing.reconcile-interval-ms:60000}")
    public void reconcile() {
        if (!routingService.routingEnabled()) {
            return;
        }
        int corrected = routingService.reconcile();
        if (corrected > 0) {
            LOGGER.info("model route reconciliation corrected {} records", corrected);
        }
    }
}
