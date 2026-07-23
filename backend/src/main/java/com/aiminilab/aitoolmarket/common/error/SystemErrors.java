package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum SystemErrors implements ErrorDefinition {
    INTERNAL_ERROR("SYSTEM_001", HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试", ErrorCategory.SYSTEM, ErrorLogLevel.ERROR, false);

    private final ErrorDescriptor descriptor;

    SystemErrors(String code,
                 HttpStatus httpStatus,
                 String defaultUserMessage,
                 ErrorCategory category,
                 ErrorLogLevel logLevel,
                 boolean retryable) {
        this.descriptor = new ErrorDescriptor(code, httpStatus, defaultUserMessage, category, logLevel, retryable);
    }

    @Override
    public ErrorDescriptor descriptor() {
        return descriptor;
    }
}
