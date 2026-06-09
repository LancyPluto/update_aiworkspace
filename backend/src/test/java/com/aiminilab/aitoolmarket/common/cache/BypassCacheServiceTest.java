package com.aiminilab.aitoolmarket.common.cache;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BypassCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private BypassCacheService bypassCacheService;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        AppProperties.Cache cache = new AppProperties.Cache();
        cache.setEnabled(true);
        appProperties.setCache(cache);
        bypassCacheService = new BypassCacheService(redisTemplate, new ObjectMapper(), appProperties);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getOrLoadUsesCachedValueWhenPresent() throws Exception {
        when(valueOperations.get("cache:test")).thenReturn("\"cached-value\"");

        String value = bypassCacheService.getOrLoad(
                "cache:test",
                Duration.ofSeconds(60),
                String.class,
                () -> "fresh-value"
        );

        assertThat(value).isEqualTo("cached-value");
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getOrLoadStoresValueWhenCacheMisses() {
        when(valueOperations.get("cache:test")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("cache:lock:cache:test"), eq("1"), any(Duration.class))).thenReturn(true);
        AtomicInteger loads = new AtomicInteger();

        String value = bypassCacheService.getOrLoad(
                "cache:test",
                Duration.ofSeconds(60),
                String.class,
                () -> {
                    loads.incrementAndGet();
                    return "fresh-value";
                }
        );

        assertThat(value).isEqualTo("fresh-value");
        assertThat(loads).hasValue(1);
        verify(valueOperations, times(1)).set(eq("cache:test"), eq("\"fresh-value\""), eq(Duration.ofSeconds(60)));
        verify(redisTemplate, times(1)).delete("cache:lock:cache:test");
    }

    @Test
    void evictWithDelayedDoubleDeleteSchedulesSecondEvict() throws Exception {
        bypassCacheService.evictWithDelayedDoubleDelete("cache:test");
        verify(redisTemplate, times(1)).delete("cache:test");
        Thread.sleep(700);
        verify(redisTemplate, times(2)).delete("cache:test");
    }

    @Test
    void disabledCacheAlwaysLoadsFromSource() {
        StringRedisTemplate unusedRedis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        AppProperties appProperties = new AppProperties();
        AppProperties.Cache cache = new AppProperties.Cache();
        cache.setEnabled(false);
        appProperties.setCache(cache);
        BypassCacheService disabled = new BypassCacheService(unusedRedis, new ObjectMapper(), appProperties);

        AtomicInteger loads = new AtomicInteger();
        disabled.getOrLoad("cache:test", Duration.ofSeconds(60), String.class, () -> {
            loads.incrementAndGet();
            return "fresh-value";
        });
        disabled.getOrLoad("cache:test", Duration.ofSeconds(60), String.class, () -> {
            loads.incrementAndGet();
            return "fresh-value";
        });

        assertThat(loads).hasValue(2);
        verify(valueOperations, never()).get(anyString());
    }
}
