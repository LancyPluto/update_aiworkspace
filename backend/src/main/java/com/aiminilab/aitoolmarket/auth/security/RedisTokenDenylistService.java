package com.aiminilab.aitoolmarket.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisTokenDenylistService implements TokenDenylistService {

    private static final String KEY_PREFIX = "auth:jwt:denylist:";

    private final StringRedisTemplate redisTemplate;

    public RedisTokenDenylistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void deny(String tokenId, Duration ttl) {
        if (tokenId == null || tokenId.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, "1", ttl);
    }

    @Override
    public boolean isDenied(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
    }
}
