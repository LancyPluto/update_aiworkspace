package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum ModelErrors implements ErrorDefinition {
    PROVIDER_CALL_FAILED("MODEL_001", HttpStatus.BAD_GATEWAY, "模型调用失败，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    PROVIDER_RESPONSE_INVALID("MODEL_002", HttpStatus.BAD_GATEWAY, "模型返回异常，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    SERVICE_UNAVAILABLE("MODEL_003", HttpStatus.SERVICE_UNAVAILABLE, "模型服务暂不可用，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    RESPONSE_TIMEOUT("MODEL_004", HttpStatus.GATEWAY_TIMEOUT, "模型响应超时，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true);

    private final ErrorDescriptor descriptor;

    ModelErrors(String code,
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
