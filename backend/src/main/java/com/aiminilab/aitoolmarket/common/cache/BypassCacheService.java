package com.aiminilab.aitoolmarket.common.cache;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class BypassCacheService {

    private static final Logger log = LoggerFactory.getLogger(BypassCacheService.class);
    private static final String LOAD_LOCK_PREFIX = CacheNamespaces.PREFIX + "lock:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final ScheduledExecutorService evictScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cache-evict-delay");
        thread.setDaemon(true);
        return thread;
    });

    public BypassCacheService(StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @PreDestroy
    void shutdown() {
        evictScheduler.shutdownNow();
    }

    public boolean isEnabled() {
        return appProperties.getCache().isEnabled();
    }

    public <T> T getOrLoad(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        return getOrLoad(key, ttl, objectMapper.constructType(type), loader);
    }

    public <T> T getOrLoad(String key, Duration ttl, JavaType javaType, Supplier<T> loader) {
        if (!isEnabled()) {
            return loader.get();
        }
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, javaType);
            }
        } catch (Exception ex) {
            log.warn("Bypass cache read failed for key={}, falling back to loader", key, ex);
        }

        String lockKey = loadLockKey(key);
        boolean acquired = tryAcquireLoadLock(lockKey);
        if (acquired) {
            try {
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return objectMapper.readValue(cached, javaType);
                }
                T value = loader.get();
                writeCache(key, ttl, value);
                return value;
            } catch (Exception ex) {
                log.warn("Bypass cache load failed for key={}, falling back to loader", key, ex);
                return loader.get();
            } finally {
                releaseLoadLock(lockKey);
            }
        }

        T waited = waitForCachedValue(key, javaType);
        if (waited != null) {
            return waited;
        }
        log.warn("Bypass cache wait timeout for key={}, loading without cache write", key);
        return loader.get();
    }

    public void evict(String key) {
        if (!isEnabled()) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception ex) {
            log.warn("Bypass cache evict failed for key={}", key, ex);
        }
    }

    public void evictWithDelayedDoubleDelete(String key) {
        evict(key);
        long delayMs = appProperties.getCache().getEvictDelayMs();
        if (delayMs <= 0) {
            return;
        }
        evictScheduler.schedule(() -> evict(key), delayMs, TimeUnit.MILLISECONDS);
    }

    public long currentToolListVersion() {
        if (!isEnabled()) {
            return 0L;
        }
        try {
            String version = redisTemplate.opsForValue().get(CacheNamespaces.TOOL_LIST_VERSION);
            return version == null ? 0L : Long.parseLong(version);
        } catch (Exception ex) {
            log.warn("Bypass cache read tool list version failed, using 0", ex);
            return 0L;
        }
    }

    public void bumpToolListVersion() {
        if (!isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().increment(CacheNamespaces.TOOL_LIST_VERSION);
        } catch (Exception ex) {
            log.warn("Bypass cache bump tool list version failed", ex);
        }
    }

    public void invalidateToolCategories() {
        evictWithDelayedDoubleDelete(CacheNamespaces.TOOL_CATEGORIES);
        bumpToolListVersion();
    }

    public void invalidateToolDetail(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return;
        }
        evictWithDelayedDoubleDelete(CacheNamespaces.toolDetail(currentToolListVersion(), toolCode));
        evictWithDelayedDoubleDelete(CacheNamespaces.toolDetail(toolCode));
    }

    public void invalidateToolCatalog(String toolCode) {
        bumpToolListVersion();
        evictWithDelayedDoubleDelete(CacheNamespaces.TOOL_CATEGORIES);
        invalidateToolDetail(toolCode);
    }

    public void invalidateRechargePackages() {
        evictWithDelayedDoubleDelete(CacheNamespaces.RECHARGE_PACKAGES);
    }

    public void invalidatePublicCustomerService() {
        evictWithDelayedDoubleDelete(CacheNamespaces.PUBLIC_CUSTOMER_SERVICE);
    }

    public void invalidateModelVendors() {
        evictWithDelayedDoubleDelete(CacheNamespaces.MODEL_VENDORS_ENABLED);
    }

    public void invalidateImportedCatalogData() {
        invalidateToolCatalog(null);
        invalidateRechargePackages();
        invalidatePublicCustomerService();
        invalidateModelVendors();
    }

    public Duration defaultTtl() {
        return Duration.ofSeconds(appProperties.getCache().getDefaultTtlSeconds());
    }

    public Duration toolTtl() {
        return Duration.ofSeconds(appProperties.getCache().getToolTtlSeconds());
    }

    public Duration packageTtl() {
        return Duration.ofSeconds(appProperties.getCache().getPackageTtlSeconds());
    }

    public Duration settingsTtl() {
        return Duration.ofSeconds(appProperties.getCache().getSettingsTtlSeconds());
    }

    public Duration vendorTtl() {
        return Duration.ofSeconds(appProperties.getCache().getVendorTtlSeconds());
    }

    private String loadLockKey(String cacheKey) {
        return LOAD_LOCK_PREFIX + cacheKey;
    }

    private boolean tryAcquireLoadLock(String lockKey) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    "1",
                    Duration.ofSeconds(appProperties.getCache().getLoadLockSeconds())
            );
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            log.warn("Bypass cache lock acquire failed for key={}, loading directly", lockKey, ex);
            return true;
        }
    }

    private void releaseLoadLock(String lockKey) {
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception ex) {
            log.warn("Bypass cache lock release failed for key={}", lockKey, ex);
        }
    }

    private <T> void writeCache(String key, Duration ttl, T value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ex) {
            log.warn("Bypass cache write failed for key={}", key, ex);
        }
    }

    private <T> T waitForCachedValue(String key, JavaType javaType) {
        int maxAttempts = appProperties.getCache().getLoadWaitMaxAttempts();
        long intervalMs = appProperties.getCache().getLoadWaitIntervalMs();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return objectMapper.readValue(cached, javaType);
                }
            } catch (Exception ex) {
                log.warn("Bypass cache wait read failed for key={}", key, ex);
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }
}
