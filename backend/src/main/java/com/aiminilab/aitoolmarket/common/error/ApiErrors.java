package com.aiminilab.aitoolmarket.common.error;

import org.springframework.http.HttpStatus;

public enum ApiErrors implements ErrorDefinition {
    JSON_BODY_UNREADABLE("API_001", HttpStatus.BAD_REQUEST, "请求内容格式不正确", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false),
    UPLOAD_TOO_LARGE("API_002", HttpStatus.PAYLOAD_TOO_LARGE, "上传文件过大", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false),
    INVALID_ARGUMENT("API_003", HttpStatus.BAD_REQUEST, "请求参数不正确", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false),
    RESOURCE_NOT_FOUND("API_004", HttpStatus.NOT_FOUND, "请求的资源不存在", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false),
    METHOD_NOT_ALLOWED("API_005", HttpStatus.METHOD_NOT_ALLOWED, "请求方法不受支持", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false),
    MEDIA_TYPE_NOT_SUPPORTED("API_006", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件或请求类型不受支持", ErrorCategory.VALIDATION, ErrorLogLevel.WARN, false);

    private final ErrorDescriptor descriptor;

    ApiErrors(String code,
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
