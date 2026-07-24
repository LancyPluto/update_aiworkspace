package com.aiminilab.aitoolmarket.common.error;

import java.util.regex.Pattern;

public final class ErrorMessageSanitizer {

    public static final int MAX_USER_MESSAGE_LENGTH = 200;
    public static final int MAX_DEVELOPER_MESSAGE_LENGTH = 2000;

    private static final String SECRET_KEY = "authorization|proxy-authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|token|password|passwd|secret|cookie|set-cookie";
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)([\\\"'](?:" + SECRET_KEY + ")[\\\"']\\s*:\\s*)([\\\"'])[^\\\"']*\\2"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(" + SECRET_KEY + ")\\b\\s*[:=]\\s*(Bearer\\s+)?([^\\s,;]+)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s,;]+");
    private static final Pattern PHONE_NUMBER = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PUBLIC_URL = Pattern.compile("(?i)https?://[^\\s]+");
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?is)\\b(select|insert|update|delete|merge|alter|drop|create|truncate)\\s+.{0,500}\\b(from|into|set|table|values)\\b"
    );
    private static final Pattern STACK_TRACE = Pattern.compile(
            "(?i)(traceback \\(most recent call last\\)|\\bat\\s+[A-Za-z0-9_.$]+\\([^)]*:[0-9]+\\)|\\bfile\\s+\"[^\"]+\",\\s+line\\s+[0-9]+)"
    );
    private static final Pattern RAW_PAYLOAD = Pattern.compile(
            "(?is)\\b(response\\s*body|responseBody|response_body|rawBody|raw_body|body|payload)\\s*[:=]\\s*.+$"
    );
    private static final Pattern JSON_LIKE_PAYLOAD = Pattern.compile(
            "(?s)(\\{\\s*\\\"[^}]*\\}|\\[\\s*(?:\\{|\\\"|[-\\d]|true\\b|false\\b|null\\b).*?\\])"
    );
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ErrorMessageSanitizer() {
    }

    public static String sanitizeUserMessage(String candidate, String fallback) {
        String safeFallback = normalizeFallback(fallback, "请求处理失败，请稍后重试");
        String normalized = normalize(candidate, safeFallback);
        if (containsRawDiagnostic(normalized)) {
            return truncate(safeFallback, MAX_USER_MESSAGE_LENGTH);
        }
        normalized = redactSecrets(normalized);
        normalized = PUBLIC_URL.matcher(normalized).replaceAll("[链接已隐藏]");
        normalized = PHONE_NUMBER.matcher(normalized).replaceAll("[手机号已隐藏]");
        return truncate(normalized, MAX_USER_MESSAGE_LENGTH);
    }

    public static String sanitizeDeveloperMessage(String candidate, String fallback) {
        String safeFallback = normalizeFallback(fallback, "Detailed diagnostics are available in server logs");
        String normalized = normalize(candidate, safeFallback);
        if (SQL_STATEMENT.matcher(normalized).find()) {
            normalized = safeFallback + " [SQL detail redacted]";
        } else if (STACK_TRACE.matcher(normalized).find()) {
            normalized = safeFallback + " [stack trace redacted]";
        } else {
            normalized = RAW_PAYLOAD.matcher(normalized).replaceFirst("$1=[REDACTED]");
            normalized = JSON_LIKE_PAYLOAD.matcher(normalized).replaceAll("[raw payload redacted]");
            normalized = redactSecrets(normalized);
            normalized = JDBC_URL.matcher(normalized).replaceAll("jdbc:[REDACTED]");
            normalized = URL_CREDENTIALS.matcher(normalized).replaceAll("$1[REDACTED]@");
            normalized = PHONE_NUMBER.matcher(normalized).replaceAll("[REDACTED_PHONE]");
        }
        return truncate(normalized, MAX_DEVELOPER_MESSAGE_LENGTH);
    }

    private static boolean containsRawDiagnostic(String value) {
        return SQL_STATEMENT.matcher(value).find()
                || STACK_TRACE.matcher(value).find()
                || RAW_PAYLOAD.matcher(value).find()
                || JSON_LIKE_PAYLOAD.matcher(value).find()
                || JDBC_URL.matcher(value).find();
    }

    private static String redactSecrets(String value) {
        String redacted = JSON_SECRET.matcher(value).replaceAll("$1$2[REDACTED]$2");
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1=[REDACTED]");
        return BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED]");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = WHITESPACE.matcher(value.strip()).replaceAll(" ");
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalizeFallback(String fallback, String defaultValue) {
        String normalized = normalize(fallback, defaultValue);
        return redactSecrets(normalized);
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 3) + "...";
    }
}
