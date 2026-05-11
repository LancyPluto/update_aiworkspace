package com.aiminilab.aitoolmarket.auth;

import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-value-with-32-bytes-minimum";

    private final InMemoryDenylistService denylistService = new InMemoryDenylistService();
    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(new ObjectMapper(), SECRET, 60, denylistService);

    @Test
    void validTokenParsesSuccessfully() {
        String token = tokenProvider.createToken(new AuthUser(42L, "alice", "USER"));

        Optional<AuthUser> parsed = tokenProvider.parseToken(token);

        assertThat(parsed).contains(new AuthUser(42L, "alice", "USER"));
        assertThat(tokenProvider.getTokenId(token)).isPresent();
        assertThat(tokenProvider.getExpiresAt(token)).isPresent();
    }

    @Test
    void wrongIssuerIsRejected() {
        String token = createToken("wrong-issuer", "ai-tool-market-client", Instant.now().plus(Duration.ofHours(1)));

        Optional<AuthUser> parsed = tokenProvider.parseToken(token);

        assertThat(parsed).isEmpty();
    }

    @Test
    void wrongAudienceIsRejected() {
        String token = createToken("ai-tool-market-backend", "wrong-audience", Instant.now().plus(Duration.ofHours(1)));

        Optional<AuthUser> parsed = tokenProvider.parseToken(token);

        assertThat(parsed).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = createToken("ai-tool-market-backend", "ai-tool-market-client", Instant.now().minus(Duration.ofMinutes(1)));

        Optional<AuthUser> parsed = tokenProvider.parseToken(token);

        assertThat(parsed).isEmpty();
    }

    @Test
    void denylistedTokenIsRejected() {
        String token = tokenProvider.createToken(new AuthUser(42L, "alice", "USER"));
        String tokenId = tokenProvider.getTokenId(token).orElseThrow();

        denylistService.deny(tokenId, Duration.ofHours(1));

        assertThat(tokenProvider.parseToken(token)).isEmpty();
    }

    private String createToken(String issuer, String audience, Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("42")
                .claim("username", "alice")
                .claim("userType", "USER")
                .issuer(issuer)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    private static final class InMemoryDenylistService implements TokenDenylistService {
        private final Set<String> deniedTokenIds = new HashSet<>();

        @Override
        public void deny(String tokenId, Duration ttl) {
            deniedTokenIds.add(tokenId);
        }

        @Override
        public boolean isDenied(String tokenId) {
            return deniedTokenIds.contains(tokenId);
        }
    }
}
