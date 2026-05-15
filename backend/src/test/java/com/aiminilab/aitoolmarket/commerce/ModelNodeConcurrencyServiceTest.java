package com.aiminilab.aitoolmarket.commerce;

import com.aiminilab.aitoolmarket.commerce.service.ModelNodeConcurrencyService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class ModelNodeConcurrencyServiceTest {

    @Test
    void reservesWithRedisTokenAndDbLedger() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(jdbcTemplate.queryForList(anyString(), eq(7L), eq(3L)))
                .thenReturn(List.of(nodeRow(2, 0)));
        Mockito.when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), Mockito.anyList(),
                        eq("2"), eq("0"), eq("300")))
                .thenReturn(1L);
        Mockito.when(jdbcTemplate.update(anyString(), eq(7L), eq(3L))).thenReturn(1);

        ModelNodeConcurrencyService service = new ModelNodeConcurrencyService(jdbcTemplate, redisTemplate);

        ModelNodeConcurrencyService.Reservation reservation = service.reserve(7L, 3L);

        assertThat(reservation.redisBacked()).isTrue();
        Mockito.verify(jdbcTemplate).update(Mockito.contains("LEAST(max_concurrency"), eq(7L), eq(3L));
    }

    @Test
    void blocksWhenRedisTokenPoolIsFull() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(jdbcTemplate.queryForList(anyString(), eq(7L), eq(3L)))
                .thenReturn(List.of(nodeRow(1, 1)));
        Mockito.when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), Mockito.anyList(),
                        eq("1"), eq("1"), eq("300")))
                .thenReturn(0L);

        ModelNodeConcurrencyService service = new ModelNodeConcurrencyService(jdbcTemplate, redisTemplate);

        assertThatThrownBy(() -> service.reserve(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHANNEL_UNAVAILABLE);
        Mockito.verify(jdbcTemplate, Mockito.never()).update(anyString(), eq(7L), eq(3L));
    }

    @Test
    void fallsBackToDbWhenRedisIsUnavailable() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(jdbcTemplate.queryForList(anyString(), eq(7L), eq(3L)))
                .thenReturn(List.of(nodeRow(2, 0)));
        Mockito.when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), Mockito.anyList(),
                        eq("2"), eq("0"), eq("300")))
                .thenThrow(new RedisConnectionFailureException("down"));
        Mockito.when(jdbcTemplate.update(anyString(), eq(7L), eq(3L))).thenReturn(1);

        ModelNodeConcurrencyService service = new ModelNodeConcurrencyService(jdbcTemplate, redisTemplate);

        ModelNodeConcurrencyService.Reservation reservation = service.reserve(7L, 3L);

        assertThat(reservation.redisBacked()).isFalse();
        Mockito.verify(jdbcTemplate).update(Mockito.contains("current_concurrency < max_concurrency"), eq(7L), eq(3L));
    }

    @Test
    void releasesDbLedgerAndRedisTokenForRedisBackedReservation() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(jdbcTemplate.update(anyString(), eq(7L))).thenReturn(1);
        Mockito.when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), Mockito.anyList(), eq("300")))
                .thenReturn(0L);

        ModelNodeConcurrencyService service = new ModelNodeConcurrencyService(jdbcTemplate, redisTemplate);

        service.release(new ModelNodeConcurrencyService.Reservation(7L, true));

        Mockito.verify(jdbcTemplate).update(Mockito.contains("current_concurrency = CASE"), eq(7L));
        Mockito.verify(redisTemplate).execute(Mockito.<RedisScript<Long>>any(), Mockito.anyList(), eq("300"));
    }

    private static Map<String, Object> nodeRow(int maxConcurrency, int currentConcurrency) {
        return Map.of(
                "max_concurrency", maxConcurrency,
                "current_concurrency", currentConcurrency
        );
    }
}
