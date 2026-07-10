package com.aiminilab.aitoolmarket.admin.audit;

import com.aiminilab.aitoolmarket.admin.entity.AdminOperationLog;
import com.aiminilab.aitoolmarket.admin.mapper.AdminOperationLogMapper;
import com.aiminilab.aitoolmarket.admin.metrics.AdminOperationMetrics;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminOperationAuditServiceImpl implements AdminOperationAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminOperationAuditServiceImpl.class);
    private static final Pattern ID_PATTERN = Pattern.compile("/(\\d+)(?:/|$)");

    private final AdminOperationLogMapper mapper;
    private final AdminOperationMetrics metrics;

    public AdminOperationAuditServiceImpl(AdminOperationLogMapper mapper, AdminOperationMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    public void record(HttpServletRequest request, int status, long durationNanos, Throwable error) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String resource = resolveResource(path);
        metrics.record(method, resource, status, durationNanos);
        try {
            AdminOperationLog log = new AdminOperationLog();
            log.setAdminId(resolveAdminId());
            log.setOperationType(method + " " + resource);
            log.setTargetType(resource);
            log.setTargetId(resolveTargetId(path));
            log.setContentJson(buildContentJson(request, status, durationNanos));
            log.setReason(resolveReason(error, status));
            log.setIpAddress(resolveIp(request));
            mapper.insert(log);
        } catch (Exception ex) {
            LOGGER.warn("failed to write admin operation audit log path={} status={}", path, status, ex);
        }
    }

    private Long resolveAdminId() {
        try {
            Long userId = AuthContext.get().userId();
            return userId == null ? 0L : userId;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String resolveResource(String path) {
        if (path == null || path.isBlank()) return "unknown";
        String prefix = "/api/admin/v1/";
        String stripped = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        String[] parts = stripped.split("/");
        return parts.length == 0 || parts[0].isBlank() ? "unknown" : parts[0];
    }

    private Long resolveTargetId(String path) {
        Matcher matcher = ID_PATTERN.matcher(path == null ? "" : path);
        if (!matcher.find()) return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String buildContentJson(HttpServletRequest request, int status, long durationNanos) {
        String path = escape(request.getRequestURI());
        String query = escape(request.getQueryString());
        String userAgent = escape(request.getHeader("User-Agent"));
        long durationMs = Math.max(0L, durationNanos / 1_000_000L);
        return "{\"path\":\"" + path + "\",\"query\":\"" + query
                + "\",\"status\":" + status + ",\"durationMs\":" + durationMs
                + ",\"userAgent\":\"" + userAgent + "\"}";
    }

    private String resolveReason(Throwable error, int status) {
        if (error == null && status < 400) return null;
        if (error instanceof BusinessException businessException) {
            return truncate(businessException.getErrorCode() + ": " + businessException.getMessage(), 512);
        }
        if (error != null) {
            return truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 512);
        }
        return "HTTP " + status;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String escape(String value) {
        if (value == null) return "";
        return truncate(value, 256).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
