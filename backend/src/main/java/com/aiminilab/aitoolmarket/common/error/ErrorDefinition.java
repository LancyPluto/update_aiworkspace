package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorDefinition {

    ErrorDescriptor descriptor();

    default String code() {
        return descriptor().code();
    }

    default HttpStatus httpStatus() {
        return descriptor().httpStatus();
    }

    default String defaultUserMessage() {
        return descriptor().defaultUserMessage();
    }

    default ErrorCategory category() {
        return descriptor().category();
    }

    default ErrorLogLevel logLevel() {
        return descriptor().logLevel();
    }

    default boolean retryable() {
        return descriptor().retryable();
    }
}
