package com.aiminilab.aitoolmarket.agent.balance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.model-vendor-balance", name = "scheduled-enabled", havingValue = "true")
public class VendorBalanceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(VendorBalanceRefreshScheduler.class);

    private final VendorBalanceRefreshService refreshService;

    public VendorBalanceRefreshScheduler(VendorBalanceRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Scheduled(fixedDelayString = "${app.model-vendor-balance.scheduled-interval-ms:600000}")
    public void refreshBalances() {
        int count = refreshService.refreshAllEnabled();
        if (count > 0) {
            log.debug("Scheduled vendor balance refresh completed for {} accounts", count);
        }
    }
}
