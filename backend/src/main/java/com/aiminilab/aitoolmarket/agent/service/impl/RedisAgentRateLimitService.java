package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentRateLimitService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.format.DateTimeFormatter;

@Service
public class RedisAgentRateLimitService implements AgentRateLimitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisAgentRateLimitService.class);
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final Duration ACTIVE_RUN_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;
    private final AgentRunMapper agentRunMapper;
    private final AppProperties appProperties;

    public RedisAgentRateLimitService(StringRedisTemplate redisTemplate,
                                      AgentRunMapper agentRunMapper,
                                      AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.agentRunMapper = agentRunMapper;
        this.appProperties = appProperties;
    }

    @Override
    public void checkMessageRate(Long userId) {
        int limit = Math.max(1, appProperties.getAgent().getMaxMessagesPerMinute());
        String key = "agent:rate:user:" + userId + ":minute:" + java.time.LocalDateTime.now().format(MINUTE_FORMAT);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(2));
            }
            if (count != null && count > limit) {
                throw new BusinessException(ErrorCode.AGENT_RATE_LIMITED, "请求过于频繁，请稍后再试");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("agent message rate limit degraded, userId={}", userId, exception);
        }
    }

    @Override
    public void checkActiveRunLimit(Long userId) {
        int limit = Math.max(1, appProperties.getAgent().getMaxActiveRunsPerUser());
        long activeRuns = agentRunMapper.countActiveRuns(userId);
        if (activeRuns >= limit) {
            throw new BusinessException(ErrorCode.AGENT_ACTIVE_RUN_LIMIT, "已有 Agent 正在运行，请稍后再试");
        }
        try {
            Long redisActiveRuns = redisTemplate.opsForSet().size(activeRunKey(userId));
            if (redisActiveRuns != null && redisActiveRuns >= limit) {
                throw new BusinessException(ErrorCode.AGENT_ACTIVE_RUN_LIMIT, "已有 Agent 正在运行，请稍后再试");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("agent active run rate limit degraded, userId={}", userId, exception);
        }
    }

    @Override
    public void checkRunRate(Long userId) {
        int limit = Math.max(1, appProperties.getAgent().getMaxRunsPerHour());
        String key = "agent:rate:user:" + userId + ":run-hour:" + java.time.LocalDateTime.now().format(HOUR_FORMAT);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(2));
            }
            if (count != null && count > limit) {
                throw new BusinessException(ErrorCode.AGENT_RATE_LIMITED, "Agent 运行次数过于频繁，请稍后再试");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("agent run rate limit degraded, userId={}", userId, exception);
        }
    }

    @Override
    public void incrementActiveRun(Long userId, Long runId) {
        try {
            SetOperations<String, String> setOperations = redisTemplate.opsForSet();
            String key = activeRunKey(userId);
            setOperations.add(key, String.valueOf(runId));
            redisTemplate.expire(key, ACTIVE_RUN_TTL);
        } catch (Exception exception) {
            LOGGER.warn("agent active run increment degraded, userId={}, runId={}", userId, runId, exception);
        }
    }

    @Override
    public void decrementActiveRun(Long userId, Long runId) {
        try {
            SetOperations<String, String> setOperations = redisTemplate.opsForSet();
            String key = activeRunKey(userId);
            setOperations.remove(key, String.valueOf(runId));
            Long remaining = setOperations.size(key);
            if (remaining != null && remaining > 0) {
                redisTemplate.expire(key, ACTIVE_RUN_TTL);
            }
        } catch (Exception exception) {
            LOGGER.warn("agent active run decrement degraded, userId={}, runId={}", userId, runId, exception);
        }
    }

    private String activeRunKey(Long userId) {
        return "agent:rate:user:" + userId + ":active-runs";
    }
}
