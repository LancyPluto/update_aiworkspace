package com.aiminilab.aitoolmarket.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentAuditPayloadExpiryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentAuditPayloadExpiryScheduler.class);
    private final AgentAuditService auditService;
    public AgentAuditPayloadExpiryScheduler(AgentAuditService auditService) { this.auditService = auditService; }

    @Scheduled(cron = "${app.agent.audit-expiry-cron:0 23 3 * * *}")
    public void expirePayloads() {
        int total = 0;
        int expired;
        do {
            expired = auditService.expirePayloads();
            total += expired;
        } while (expired == 1000 && total < 100_000);
        if (total > 0) LOGGER.info("Expired {} agent audit payloads", total);
    }
}
