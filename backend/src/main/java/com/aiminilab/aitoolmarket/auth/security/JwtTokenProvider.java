package com.aiminilab.aitoolmarket.auth.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class JwtTokenProvider {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final String secret;
    private final long expireMinutes;

    public JwtTokenProvider(
            ObjectMapper objectMapper,
            @Value("${app.jwt-secret}") String secret,
            @Value("${JWT_EXPIRE_MINUTES:1440}") long expireMinutes
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.expireMinutes = expireMinutes;
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    public String createToken(AuthUser authUser) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", authUser.userId());
            payload.put("username", authUser.username());
            payload.put("userType", authUser.userType());
            payload.put("exp", Instant.now().plusSeconds(expireMinutes * 60).getEpochSecond());

            String headerPart = encodeJson(header);
            String payloadPart = encodeJson(payload);
            String signature = sign(headerPart + "." + payloadPart);
            return headerPart + "." + payloadPart + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create token", exception);
        }
    }

    public Optional<AuthUser> parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String expectedSignature = sign(parts[0] + "." + parts[1]);
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return Optional.empty();
            }
            Map<String, Object> payload = objectMapper.readValue(
                    BASE64_URL_DECODER.decode(parts[1]),
                    new TypeReference<>() {
                    }
            );
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() > exp) {
                return Optional.empty();
            }
            Long userId = ((Number) payload.get("userId")).longValue();
            String username = String.valueOf(payload.get("username"));
            String userType = String.valueOf(payload.get("userType"));
            return Optional.of(new AuthUser(userId, username, userType));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String encodeJson(Map<String, Object> value) throws Exception {
        return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private String sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return BASE64_URL_ENCODER.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigestSupport.equals(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MessageDigestSupport {
        private MessageDigestSupport() {
        }

        static boolean equals(byte[] left, byte[] right) {
            if (left.length != right.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < left.length; i++) {
                result |= left[i] ^ right[i];
            }
            return result == 0;
        }
    }
}
