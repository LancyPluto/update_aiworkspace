package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.agent", name = "stale-run-recovery-enabled", havingValue = "true", matchIfMissing = true)
public class AgentRunStaleRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStaleRecoveryScheduler.class);

    private final AgentRunMapper agentRunMapper;
    private final AgentRunService agentRunService;
    private final long staleAfterMinutes;
    private final int batchSize;

    public AgentRunStaleRecoveryScheduler(AgentRunMapper agentRunMapper,
                                          AgentRunService agentRunService,
                                          @Value("${app.agent.stale-run-after-minutes:15}") long staleAfterMinutes,
                                          @Value("${app.agent.stale-run-recovery-batch-size:20}") int batchSize) {
        this.agentRunMapper = agentRunMapper;
        this.agentRunService = agentRunService;
        this.staleAfterMinutes = Math.max(5L, staleAfterMinutes);
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleRunsOnStartup() {
        recoverStaleRuns();
    }

    @Scheduled(fixedDelayString = "${app.agent.stale-run-recovery-interval-ms:60000}")
    public void recoverStaleRuns() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleAfterMinutes);
        List<AgentRun> staleRuns = agentRunMapper.findStaleActiveRuns(cutoff, batchSize);
        if (staleRuns.isEmpty()) {
            return;
        }
        for (AgentRun run : staleRuns) {
            try {
                agentRunService.failRun(
                        run.getId(),
                        new FailAgentRunRequest(
                                "AGENT_RUN_STALE",
                                "Agent 运行超时未结束，已自动回收。请重新发送消息。"
                        )
                );
                log.warn("Recovered stale agent run id={} userId={} status={} updatedAt={}",
                        run.getId(), run.getUserId(), run.getStatus(), run.getUpdatedAt());
            } catch (Exception exception) {
                log.warn("Failed to recover stale agent run id={}", run.getId(), exception);
            }
        }
    }
}
