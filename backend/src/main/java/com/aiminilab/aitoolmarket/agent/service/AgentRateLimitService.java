package com.aiminilab.aitoolmarket.agent.service;

public interface AgentRateLimitService {
    void checkMessageRate(Long userId);

    void checkRunRate(Long userId);

    void checkActiveRunLimit(Long userId);

    void incrementActiveRun(Long userId, Long runId);

    void decrementActiveRun(Long userId, Long runId);
}
