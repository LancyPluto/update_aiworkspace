package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum TaskErrors implements ErrorDefinition {
    TASK_NOT_FOUND("TASK_001", HttpStatus.NOT_FOUND, "任务不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    TASK_STATE_CONFLICT("TASK_002", HttpStatus.CONFLICT, "任务当前状态不允许该操作", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    IDEMPOTENCY_CONFLICT("TASK_003", HttpStatus.CONFLICT, "请求已被处理，请勿重复提交", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false);

    private final ErrorDescriptor descriptor;

    TaskErrors(String code,
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
