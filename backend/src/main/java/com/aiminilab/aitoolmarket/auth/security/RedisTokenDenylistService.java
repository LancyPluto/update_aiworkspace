package com.aiminilab.aitoolmarket.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RedisTokenDenylistService implements TokenDenylistService {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenDenylistService.class);
    private static final String KEY_PREFIX = "auth:jwt:denylist:";

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Instant> localDenylist = new ConcurrentHashMap<>();

    public RedisTokenDenylistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void deny(String tokenId, Duration ttl) {
        if (tokenId == null || tokenId.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, "1", ttl);
        } catch (RuntimeException exception) {
            localDenylist.put(tokenId, Instant.now().plus(ttl));
            log.warn("Redis unavailable while denying JWT token; using local denylist fallback");
        }
    }

    @Override
    public boolean isDenied(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        if (isLocallyDenied(tokenId)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable while checking JWT denylist; allowing non-local token");
            return false;
        }
    }

    private boolean isLocallyDenied(String tokenId) {
        Instant expiresAt = localDenylist.get(tokenId);
        if (expiresAt == null) {
            return false;
        }
        if (Instant.now().isBefore(expiresAt)) {
            return true;
        }
        localDenylist.remove(tokenId, expiresAt);
        return false;
    }
}
