package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.service.impl.RedisAgentRateLimitService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
}
