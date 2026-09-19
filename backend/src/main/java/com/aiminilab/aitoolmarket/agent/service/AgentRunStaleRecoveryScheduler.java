package com.aiminilab.aitoolmarket.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "app.agent", name = "auto-recovery-enabled", havingValue = "true", matchIfMissing = false)
public class AgentRunStaleRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStaleRecoveryScheduler.class);

    private final AgentRunRecoveryService recovery;
    private final long staleAfterMinutes;
    private final int batchSize;

    public AgentRunStaleRecoveryScheduler(AgentRunRecoveryService recovery,
            @Value("${app.agent.stale-run-after-minutes:15}") long staleAfterMinutes,
            @Value("${app.agent.stale-run-recovery-batch-size:20}") int batchSize) {
        this.recovery = recovery;
        this.staleAfterMinutes = Math.max(1, staleAfterMinutes);
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleRunsOnStartup() { recoverStaleRuns(); }

    @Scheduled(fixedDelayString = "${app.agent.stale-run-recovery-interval-ms:60000}")
    public void recoverStaleRuns() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleAfterMinutes);
        for (Long runId : recovery.candidates(cutoff, batchSize)) {
            try { recovery.dispatch(runId, cutoff); }
            catch (RuntimeException e) { log.warn("Could not recover agent run id={}", runId, e); }
        }
    }
}
