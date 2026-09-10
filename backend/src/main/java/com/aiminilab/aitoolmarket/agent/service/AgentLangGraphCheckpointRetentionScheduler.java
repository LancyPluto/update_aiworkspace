package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.agent.langgraph-retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentLangGraphCheckpointRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentLangGraphCheckpointRetentionScheduler.class);
    private final AgentRunMapper mapper;
    private final int batchSize;
    private final int retentionDays;

    public AgentLangGraphCheckpointRetentionScheduler(AgentRunMapper mapper,
            @Value("${app.agent.langgraph-retention.batch-size:500}") int batchSize,
            @Value("${app.agent.langgraph-retention.days:30}") int retentionDays) {
        this.mapper = mapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 5000));
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${app.agent.langgraph-retention.cron:0 35 3 * * *}")
    public void purgeExpiredCheckpoints() {
        int total = 0;
        while (true) {
            List<Long> runIds = mapper.findExpiredLangGraphCheckpointRuns(
                    LocalDateTime.now().minusDays(retentionDays), batchSize);
            if (runIds.isEmpty()) break;
            mapper.deleteExpiredLangGraphCheckpointWrites(runIds);
            total += mapper.deleteExpiredLangGraphCheckpoints(runIds);
            if (runIds.size() < batchSize) break;
        }
        if (total > 0) log.info("Purged {} expired LangGraph checkpoint rows", total);
    }
}
