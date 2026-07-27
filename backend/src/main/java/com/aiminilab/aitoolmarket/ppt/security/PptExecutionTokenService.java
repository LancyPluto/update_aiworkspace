package com.aiminilab.aitoolmarket.ppt.security;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PptExecutionTokenService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String PREFIX = "kcdppt1";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public PptExecutionTokenService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public IssuedToken issue(Long projectId, Long jobId, List<String> capabilities, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + Math.max(60, Math.min(ttlSeconds, 3600));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("projectId", projectId);
        payload.put("jobId", jobId);
        payload.put("iat", now);
        payload.put("exp", expiresAt);
        var array = payload.putArray("capabilities");
        normalizeCapabilities(capabilities).forEach(array::add);
        try {
            String encoded = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String signingInput = PREFIX + "." + encoded;
            String signature = URL_ENCODER.encodeToString(sign(signingInput));
            return new IssuedToken(signingInput + "." + signature, expiresAt);
        } catch (Exception exception) {
            throw new IllegalStateException("PPT execution token creation failed", exception);
        }
    }

    public Claims require(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            throw unauthorized();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw unauthorized();
        }
        try {
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = sign(signingInput);
            byte[] actual = URL_DECODER.decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                throw unauthorized();
            }
            var node = objectMapper.readTree(URL_DECODER.decode(parts[1]));
            long expiresAt = node.path("exp").asLong(0);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "PPT 执行令牌已过期");
            }
            Set<String> capabilities = new LinkedHashSet<>();
            node.path("capabilities").forEach(item -> capabilities.add(item.asText().toUpperCase()));
            Long projectId = node.path("projectId").canConvertToLong() ? node.path("projectId").asLong() : null;
            Long jobId = node.path("jobId").canConvertToLong() ? node.path("jobId").asLong() : null;
            if (projectId == null || jobId == null || capabilities.isEmpty()) {
                throw unauthorized();
            }
            return new Claims(node.path("jti").asText(), projectId, jobId, capabilities, expiresAt);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unauthorized();
        }
    }

    public boolean accepts(String authorizationHeader) {
        try {
            require(authorizationHeader);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private byte[] sign(String value) throws Exception {
        String secret = appProperties.getInternalApiToken();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.internal-api-token is required");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String bearerToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private Set<String> normalizeCapabilities(List<String> capabilities) {
        Set<String> normalized = new LinkedHashSet<>();
        if (capabilities != null) {
            capabilities.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase())
                    .forEach(normalized::add);
        }
        return normalized;
    }

    private BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "PPT 执行令牌无效");
    }

    public record IssuedToken(String token, long expiresAtEpochSeconds) {}

    public record Claims(String tokenId, Long projectId, Long jobId,
                         Set<String> capabilities, long expiresAtEpochSeconds) {
        public void requireScope(Long requestedProjectId, Long requestedJobId, String capability) {
            if (!projectId.equals(requestedProjectId)
                    || !jobId.equals(requestedJobId)
                    || !capabilities.contains(capability.toUpperCase())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "PPT 执行令牌作用域不匹配");
            }
        }
    }
}
