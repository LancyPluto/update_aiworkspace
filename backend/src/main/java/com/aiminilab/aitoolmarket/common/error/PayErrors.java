package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum PayErrors implements ErrorDefinition {
    PROVIDER_CALL_FAILED("PAY_001", HttpStatus.BAD_GATEWAY, "支付服务暂不可用，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    PROVIDER_RESPONSE_INVALID("PAY_002", HttpStatus.BAD_GATEWAY, "支付服务返回异常，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    PROVIDER_TIMEOUT("PAY_003", HttpStatus.GATEWAY_TIMEOUT, "支付服务响应超时，请稍后重试", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    CALLBACK_INVALID("PAY_004", HttpStatus.BAD_REQUEST, "支付通知校验失败", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false),
    SERVICE_NOT_CONFIGURED("PAY_005", HttpStatus.SERVICE_UNAVAILABLE, "支付服务暂不可用，请稍后重试", ErrorCategory.SYSTEM, ErrorLogLevel.ERROR, false);

    private final ErrorDescriptor descriptor;

    PayErrors(String code,
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
