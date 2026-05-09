package com.aiminilab.aitoolmarket.auth;

import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalRequestSignatureVerifierTest {

    private static final String SECRET = "test-internal-secret";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private InternalRequestSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), eq("1"), eq(Duration.ofMinutes(5)))).thenReturn(true);
        verifier = new InternalRequestSignatureVerifier(redisTemplate, SECRET);
    }

    @Test
    void validSignaturePasses() {
        byte[] body = "{\"progress\":35}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = "nonce-valid";
        String signature = sign("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, body);

        assertThat(verifier.verify("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, signature, body))
                .isTrue();
    }

    @Test
    void wrongBodyFails() {
        byte[] signedBody = "{\"progress\":35}".getBytes(StandardCharsets.UTF_8);
        byte[] actualBody = "{\"progress\":36}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = "nonce-wrong-body";
        String signature = sign("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, signedBody);

        assertThat(verifier.verify("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, signature, actualBody))
                .isFalse();
    }

    @Test
    void oldTimestampFails() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().minus(Duration.ofMinutes(6)).toEpochMilli());
        String nonce = "nonce-old";
        String signature = sign("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, body);

        assertThat(verifier.verify("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, signature, body))
                .isFalse();
    }

    @Test
    void reusedNonceFails() {
        when(valueOperations.setIfAbsent(eq("internal-api:nonce:nonce-reused"), eq("1"), eq(Duration.ofMinutes(5))))
                .thenReturn(false);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = "nonce-reused";
        String signature = sign("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, body);

        assertThat(verifier.verify("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, signature, body))
                .isFalse();
    }

    @Test
    void missingSignatureFails() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());

        assertThat(verifier.verify("POST", "/api/internal/v1/tasks/1/processing", timestamp, "nonce-missing", null, body))
                .isFalse();
    }

    private String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String content = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
