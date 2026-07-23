package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum ToolErrors implements ErrorDefinition {
    TOOL_NOT_FOUND("TOOL_001", HttpStatus.NOT_FOUND, "工具不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    TOOL_STATE_CONFLICT("TOOL_002", HttpStatus.CONFLICT, "工具当前不可用", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false);

    private final ErrorDescriptor descriptor;

    ToolErrors(String code,
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
