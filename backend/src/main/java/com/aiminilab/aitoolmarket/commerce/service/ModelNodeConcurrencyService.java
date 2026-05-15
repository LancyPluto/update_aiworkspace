package com.aiminilab.aitoolmarket.commerce.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ModelNodeConcurrencyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelNodeConcurrencyService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "commerce:model-node:concurrency:";
    private static final RedisScript<Long> ACQUIRE_SCRIPT = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              current = tonumber(ARGV[2]) or 0
            else
              current = tonumber(current) or 0
            end
            local limit = tonumber(ARGV[1])
            if current >= limit then
              return 0
            end
            current = current + 1
            redis.call('SET', KEYS[1], current, 'EX', ARGV[3])
            return 1
            """, Long.class);
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 0
            end
            current = tonumber(current) or 0
            if current <= 1 then
              redis.call('DEL', KEYS[1])
              return 0
            end
            current = current - 1
            redis.call('SET', KEYS[1], current, 'EX', ARGV[1])
            return current
            """, Long.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public ModelNodeConcurrencyService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    public Reservation reserve(Long nodeId, Long poolId) {
        Map<String, Object> node = findNode(nodeId, poolId);
        int maxConcurrency = Math.max(1, ((Number) node.get("max_concurrency")).intValue());
        int currentConcurrency = Math.max(0, ((Number) node.get("current_concurrency")).intValue());
        try {
            if (!acquireRedisToken(nodeId, maxConcurrency, currentConcurrency)) {
                throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "Model node is busy or unavailable");
            }
            try {
                incrementDbLedger(nodeId, poolId, false);
            } catch (RuntimeException exception) {
                releaseRedisToken(nodeId);
                throw exception;
            }
            return new Reservation(nodeId, true);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis model node token unavailable; falling back to DB, nodeId={}", nodeId, exception);
            incrementDbLedger(nodeId, poolId, true);
            return new Reservation(nodeId, false);
        }
    }

    public void release(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        releaseDbLedger(reservation.nodeId());
        if (reservation.redisBacked()) {
            releaseRedisToken(reservation.nodeId());
        }
    }

    private Map<String, Object> findNode(Long nodeId, Long poolId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT max_concurrency, current_concurrency
                FROM model_channel_nodes
                WHERE id = ? AND pool_id = ?
                """, nodeId, poolId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "Model node is busy or unavailable");
        }
        return rows.get(0);
    }

    private boolean acquireRedisToken(Long nodeId, int maxConcurrency, int currentConcurrency) {
        Long acquired = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(redisKey(nodeId)),
                String.valueOf(maxConcurrency),
                String.valueOf(currentConcurrency),
                String.valueOf(TOKEN_TTL.toSeconds())
        );
        return acquired != null && acquired == 1L;
    }

    private void releaseRedisToken(Long nodeId) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(redisKey(nodeId)), String.valueOf(TOKEN_TTL.toSeconds()));
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis model node token release failed, nodeId={}", nodeId, exception);
        }
    }

    private void incrementDbLedger(Long nodeId, Long poolId, boolean enforceDbConcurrency) {
        String concurrencyCondition = enforceDbConcurrency ? "AND current_concurrency < max_concurrency" : "";
        int updated = jdbcTemplate.update("""
                UPDATE model_channel_nodes
                SET current_concurrency = LEAST(max_concurrency, current_concurrency + 1),
                    today_used = today_used + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND pool_id = ?
                  AND status = 'AVAILABLE'
                  %s
                  AND (daily_limit IS NULL OR today_used < daily_limit)
                """.formatted(concurrencyCondition), nodeId, poolId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "Model node is busy or unavailable");
        }
    }

    private void releaseDbLedger(Long nodeId) {
        jdbcTemplate.update("""
                UPDATE model_channel_nodes
                SET current_concurrency = CASE WHEN current_concurrency > 0 THEN current_concurrency - 1 ELSE 0 END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, nodeId);
    }

    private String redisKey(Long nodeId) {
        return KEY_PREFIX + nodeId;
    }

    public record Reservation(Long nodeId, boolean redisBacked) {
    }
}
