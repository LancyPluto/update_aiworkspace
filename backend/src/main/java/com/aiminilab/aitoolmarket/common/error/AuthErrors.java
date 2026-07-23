package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum AuthErrors implements ErrorDefinition {
    CREDENTIALS_MISSING("AUTH_001", HttpStatus.UNAUTHORIZED, "请先登录", ErrorCategory.AUTHENTICATION, ErrorLogLevel.WARN, false),
    CREDENTIALS_EXPIRED("AUTH_002", HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录", ErrorCategory.AUTHENTICATION, ErrorLogLevel.WARN, false),
    ACCESS_DENIED("AUTH_003", HttpStatus.FORBIDDEN, "无权访问该资源", ErrorCategory.AUTHORIZATION, ErrorLogLevel.WARN, false);

    private final ErrorDescriptor descriptor;

    AuthErrors(String code,
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
