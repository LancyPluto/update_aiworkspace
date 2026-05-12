package com.aiminilab.aitoolmarket.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String ISSUER = "ai-tool-market-backend";
    private static final String AUDIENCE = "ai-tool-market-client";

    private final SecretKey signingKey;
    private final long expireMinutes;
    private final TokenDenylistService tokenDenylistService;

    public JwtTokenProvider(
            ObjectMapper objectMapper,
            @Value("${app.jwt-secret}") String secret,
            @Value("${JWT_EXPIRE_MINUTES:1440}") long expireMinutes,
            TokenDenylistService tokenDenylistService
    ) {
        this.signingKey = signingKey(secret);
        this.expireMinutes = expireMinutes;
        this.tokenDenylistService = tokenDenylistService;
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    public String createToken(AuthUser authUser) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(expireMinutes));
        return Jwts.builder()
                .subject(String.valueOf(authUser.userId()))
                .claim("username", authUser.username())
                .claim("userType", authUser.userType())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public Optional<AuthUser> parseToken(String token) {
        try {
            Claims claims = parseRequiredClaims(token);
            String tokenId = claims.getId();
            if (tokenDenylistService.isDenied(tokenId)) {
                return Optional.empty();
            }
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            String userType = claims.get("userType", String.class);
            if (username == null || userType == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthUser(userId, username, userType));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public void revokeToken(String token) {
        Optional<Claims> claims = readVerifiedClaims(token);
        if (claims.isEmpty()) {
            return;
        }
        String tokenId = claims.get().getId();
        Date expiration = claims.get().getExpiration();
        if (tokenId == null || expiration == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiration.toInstant());
        tokenDenylistService.deny(tokenId, ttl);
    }

    public Optional<String> getTokenId(String token) {
        return readVerifiedClaims(token).map(Claims::getId);
    }

    public Optional<Instant> getExpiresAt(String token) {
        return readVerifiedClaims(token)
                .map(Claims::getExpiration)
                .map(Date::toInstant);
    }

    private Claims parseRequiredClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Optional<Claims> readVerifiedClaims(String token) {
        try {
            return Optional.of(parseRequiredClaims(token));
        } catch (ExpiredJwtException exception) {
            return Optional.ofNullable(exception.getClaims());
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private SecretKey signingKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length >= 32) {
            return Keys.hmacShaKeyFor(keyBytes);
        }
        return Keys.hmacShaKeyFor(sha256(keyBytes));
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create JWT signing key", exception);
        }
    }
}
