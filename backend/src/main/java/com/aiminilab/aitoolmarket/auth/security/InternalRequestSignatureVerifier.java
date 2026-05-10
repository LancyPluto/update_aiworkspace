package com.aiminilab.aitoolmarket.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InternalRequestSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(InternalRequestSignatureVerifier.class);
    private static final Duration SIGNATURE_TTL = Duration.ofMinutes(5);
    private static final String NONCE_KEY_PREFIX = "internal-api:nonce:";

    private final StringRedisTemplate redisTemplate;
    private final String secret;
    private final Map<String, Instant> localNonces = new ConcurrentHashMap<>();

    public InternalRequestSignatureVerifier(StringRedisTemplate redisTemplate,
                                            @Value("${app.internal-api-token}") String secret) {
        this.redisTemplate = redisTemplate;
        this.secret = secret;
    }

    public boolean verify(String method,
                          String path,
                          String timestamp,
                          String nonce,
                          String signature,
                          byte[] body) {
        if (isBlank(method) || isBlank(path) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return false;
        }
        if (!timestampIsFresh(timestamp)) {
            return false;
        }

        String expectedSignature = sign(method.toUpperCase(), path, timestamp, nonce, body == null ? new byte[0] : body);
        if (!constantTimeEquals(expectedSignature, signature)) {
            return false;
        }

        try {
            Boolean stored = redisTemplate.opsForValue()
                    .setIfAbsent(NONCE_KEY_PREFIX + nonce, "1", SIGNATURE_TTL);
            return Boolean.TRUE.equals(stored);
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable while storing internal API nonce; using local nonce fallback");
            return storeLocalNonce(nonce);
        }
    }

    private boolean timestampIsFresh(String timestamp) {
        try {
            long timestampMillis = Long.parseLong(timestamp);
            long ageMillis = Math.abs(Instant.now().toEpochMilli() - timestampMillis);
            return ageMillis <= SIGNATURE_TTL.toMillis();
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        try {
            String bodyHash = sha256Hex(body);
            String content = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not verify internal request signature", exception);
        }
    }

    private String sha256Hex(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean storeLocalNonce(String nonce) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SIGNATURE_TTL);
        localNonces.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
        return localNonces.putIfAbsent(nonce, expiresAt) == null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
