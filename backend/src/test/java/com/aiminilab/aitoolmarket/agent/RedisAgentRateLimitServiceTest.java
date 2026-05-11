package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.service.impl.RedisAgentRateLimitService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;

class RedisAgentRateLimitServiceTest {

    @Test
    void blocksMessagesOverPerMinuteLimit() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(startsWith("agent:rate:user:1:minute:"))).thenReturn(2L);

        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxMessagesPerMinute(1);
        RedisAgentRateLimitService service = new RedisAgentRateLimitService(redisTemplate, Mockito.mock(AgentRunMapper.class), properties);

        assertThatThrownBy(() -> service.checkMessageRate(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AGENT_RATE_LIMITED);
    }

    @Test
    void blocksRunsOverPerHourLimit() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(startsWith("agent:rate:user:1:run-hour:"))).thenReturn(2L);

        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxRunsPerHour(1);
        RedisAgentRateLimitService service = new RedisAgentRateLimitService(redisTemplate, Mockito.mock(AgentRunMapper.class), properties);

        assertThatThrownBy(() -> service.checkRunRate(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AGENT_RATE_LIMITED);
    }

    @Test
    void blocksActiveRunsWhenRedisSetReachesLimit() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        AgentRunMapper agentRunMapper = Mockito.mock(AgentRunMapper.class);
        Mockito.when(redisTemplate.opsForSet()).thenReturn(setOperations);
        Mockito.when(agentRunMapper.countActiveRuns(1L)).thenReturn(0L);
        Mockito.when(setOperations.size("agent:rate:user:1:active-runs")).thenReturn(1L);

        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxActiveRunsPerUser(1);
        RedisAgentRateLimitService service = new RedisAgentRateLimitService(redisTemplate, agentRunMapper, properties);

        assertThatThrownBy(() -> service.checkActiveRunLimit(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AGENT_ACTIVE_RUN_LIMIT);
    }

    @Test
    void tracksActiveRunsInRedisOnIncrementAndDecrement() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        Mockito.when(redisTemplate.opsForSet()).thenReturn(setOperations);
        Mockito.when(setOperations.size("agent:rate:user:3:active-runs")).thenReturn(0L);

        RedisAgentRateLimitService service = new RedisAgentRateLimitService(
                redisTemplate,
                Mockito.mock(AgentRunMapper.class),
                new AppProperties()
        );

        service.incrementActiveRun(3L, 88L);
        service.decrementActiveRun(3L, 88L);

        Mockito.verify(setOperations).add("agent:rate:user:3:active-runs", "88");
        Mockito.verify(setOperations).remove("agent:rate:user:3:active-runs", "88");
        Mockito.verify(redisTemplate).expire("agent:rate:user:3:active-runs", Duration.ofHours(6));
        Mockito.verify(setOperations).size("agent:rate:user:3:active-runs");
    }
}
