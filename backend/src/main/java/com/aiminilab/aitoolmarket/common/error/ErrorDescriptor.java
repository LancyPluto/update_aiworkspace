package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

import java.util.Objects;
import java.util.regex.Pattern;

public record ErrorDescriptor(
        String code,
        HttpStatus httpStatus,
        String defaultUserMessage,
        ErrorCategory category,
        ErrorLogLevel logLevel,
        boolean retryable
) {
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]*_[0-9]{3}$");

    public ErrorDescriptor {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid application error code: " + code);
        }
        Objects.requireNonNull(httpStatus, "httpStatus");
        if (defaultUserMessage == null || defaultUserMessage.isBlank()) {
            throw new IllegalArgumentException("defaultUserMessage must not be blank");
        }
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(logLevel, "logLevel");
    }
}
