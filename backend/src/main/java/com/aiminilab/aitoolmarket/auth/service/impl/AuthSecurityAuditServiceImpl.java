package com.aiminilab.aitoolmarket.auth.service.impl;

import com.aiminilab.aitoolmarket.auth.mapper.AuthSecurityEventMapper;
import com.aiminilab.aitoolmarket.auth.metrics.AuthMetrics;
import com.aiminilab.aitoolmarket.auth.service.AuthSecurityAuditService;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class AuthSecurityAuditServiceImpl implements AuthSecurityAuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthSecurityAuditServiceImpl.class);

    private final AuthSecurityEventMapper mapper;
    private final AuthMetrics authMetrics;
    private final boolean enabled;
    private final String geoIpDatabasePath;
    private final String accountHashPepper;
    private volatile DatabaseReader geoIpReader;
    private volatile boolean geoIpUnavailableLogged;

    public AuthSecurityAuditServiceImpl(AuthSecurityEventMapper mapper,
                                        AuthMetrics authMetrics,
                                        @Value("${app.auth.security-audit.enabled:true}") boolean enabled,
                                        @Value("${app.auth.security-audit.geoip.database-path:}") String geoIpDatabasePath,
                                        @Value("${app.auth.security-audit.account-hash-pepper:${app.jwt-secret:}}") String accountHashPepper) {
        this.mapper = mapper;
        this.authMetrics = authMetrics;
        this.enabled = enabled;
        this.geoIpDatabasePath = geoIpDatabasePath;
        this.accountHashPepper = accountHashPepper;
    }

    @Override
    public void record(HttpServletRequest request,
                       String eventType,
                       String result,
                       String method,
                       String userType,
                       Long userId,
                       String account,
                       String failureReason) {
        if (!enabled) {
            return;
        }
        recordMetric(result, eventType, method, userType, failureReason);
        try {
            String ip = resolveClientIp(request);
            GeoLocation location = lookup(ip);
            mapper.insert(
                    normalize(eventType, "UNKNOWN", 64),
                    normalize(result, "UNKNOWN", 16),
                    normalize(method, "unknown", 32),
                    normalize(userType, "UNKNOWN", 16),
                    userId,
                    hashAccount(account),
                    maskAccount(account),
                    normalize(failureReason, null, 128),
                    normalize(ip, null, 64),
                    normalize(request == null ? null : request.getHeader("User-Agent"), null, 512),
                    normalize(MDC.get("traceId"), null, 64),
                    location.country(),
                    location.region(),
                    location.city(),
                    location.latitude(),
                    location.longitude()
            );
        } catch (Exception exception) {
            LOGGER.warn("failed to write auth security event type={} result={}", eventType, result, exception);
        }
    }

    private void recordMetric(String result, String eventType, String method, String userType, String failureReason) {
        try {
            authMetrics.recordSecurityEvent(result, eventType, method, userType, failureReason);
        } catch (Exception exception) {
            LOGGER.warn("failed to record auth security metric type={} result={}", eventType, result, exception);
        }
    }

    private GeoLocation lookup(String ip) {
        if (ip == null || ip.isBlank() || isPrivateOrLocalIp(ip)) {
            return GeoLocation.empty();
        }
        DatabaseReader reader = geoIpReader();
        if (reader == null) {
            return GeoLocation.empty();
        }
        try {
            CityResponse response = reader.city(InetAddress.getByName(ip));
            String country = response.getCountry() == null ? null : response.getCountry().getNames().getOrDefault("zh-CN", response.getCountry().getName());
            String region = response.getMostSpecificSubdivision() == null ? null : response.getMostSpecificSubdivision().getNames().getOrDefault("zh-CN", response.getMostSpecificSubdivision().getName());
            String city = response.getCity() == null ? null : response.getCity().getNames().getOrDefault("zh-CN", response.getCity().getName());
            BigDecimal latitude = response.getLocation() == null || response.getLocation().getLatitude() == null
                    ? null
                    : BigDecimal.valueOf(response.getLocation().getLatitude());
            BigDecimal longitude = response.getLocation() == null || response.getLocation().getLongitude() == null
                    ? null
                    : BigDecimal.valueOf(response.getLocation().getLongitude());
            return new GeoLocation(country, region, city, latitude, longitude);
        } catch (Exception exception) {
            return GeoLocation.empty();
        }
    }

    private DatabaseReader geoIpReader() {
        DatabaseReader current = geoIpReader;
        if (current != null) {
            return current;
        }
        if (geoIpDatabasePath == null || geoIpDatabasePath.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (geoIpReader != null) {
                return geoIpReader;
            }
            try {
                File database = new File(geoIpDatabasePath);
                if (!database.isFile()) {
                    logGeoIpUnavailable("GeoIP database not found: " + geoIpDatabasePath);
                    return null;
                }
                geoIpReader = new DatabaseReader.Builder(database).build();
                return geoIpReader;
            } catch (Exception exception) {
                logGeoIpUnavailable("GeoIP database unavailable: " + exception.getMessage());
                return null;
            }
        }
    }

    private void logGeoIpUnavailable(String message) {
        if (!geoIpUnavailableLogged) {
            geoIpUnavailableLogged = true;
            LOGGER.warn(message);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remote = request.getRemoteAddr();
        if (isTrustedProxyRemote(remote)) {
            String forwarded = firstForwardedIp(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
            String realIp = normalize(request.getHeader("X-Real-IP"), null, 64);
            if (realIp != null) {
                return realIp;
            }
        }
        return normalize(remote, null, 64);
    }

    private String firstForwardedIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String item : value.split(",")) {
            String ip = normalize(item, null, 64);
            if (ip != null) {
                return ip;
            }
        }
        return null;
    }

    private boolean isTrustedProxyRemote(String remote) {
        return remote == null || remote.isBlank() || isPrivateOrLocalIp(remote);
    }

    private boolean isPrivateOrLocalIp(String ip) {
        String value = ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
        if (value.equals("localhost")
                || value.equals("127.0.0.1")
                || value.equals("0:0:0:0:0:0:0:1")
                || value.equals("::1")) {
            return true;
        }
        if (value.startsWith("10.") || value.startsWith("192.168.")) {
            return true;
        }
        if (!value.startsWith("172.")) {
            return false;
        }
        String[] parts = value.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int secondOctet = Integer.parseInt(parts[1]);
            return secondOctet >= 16 && secondOctet <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String maskAccount(String account) {
        String normalized = normalize(account, null, 128);
        if (normalized == null) {
            return null;
        }
        String compact = normalized.replaceAll("[\\s-]", "");
        if (compact.matches("^1\\d{10}$")) {
            return compact.substring(0, 3) + "****" + compact.substring(7);
        }
        if (normalized.contains("@")) {
            int at = normalized.indexOf('@');
            String head = normalized.substring(0, Math.min(2, at));
            return head + "***" + normalized.substring(at);
        }
        if (normalized.length() <= 2) {
            return "***";
        }
        return normalized.substring(0, Math.min(2, normalized.length())) + "***";
    }

    private String hashAccount(String account) {
        String normalized = normalize(account, null, 256);
        if (normalized == null) {
            return null;
        }
        try {
            String normalizedLower = normalized.toLowerCase(Locale.ROOT);
            if (accountHashPepper != null && !accountHashPepper.isBlank()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(accountHashPepper.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(normalizedLower.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedLower.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalize(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private record GeoLocation(String country, String region, String city, BigDecimal latitude, BigDecimal longitude) {
        static GeoLocation empty() {
            return new GeoLocation(null, null, null, null, null);
        }
    }
}
