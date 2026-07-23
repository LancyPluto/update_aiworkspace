package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.error.ErrorCategory;
import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;
import com.aiminilab.aitoolmarket.common.error.LegacyErrorCodeMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;

import java.util.Map;

public class BusinessException extends BizException {

    private final ErrorCode errorCode;
    private final Object data;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Object data) {
        super(
                LegacyErrorCodeMapper.fromLegacy(errorCode),
                legacyUserMessage(errorCode, message),
                message,
                null,
                Map.of("legacyErrorCode", errorCode.name())
        );
        this.errorCode = errorCode;
        this.data = data;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getData() {
        return data;
    }

    private static String legacyUserMessage(ErrorCode errorCode, String message) {
        ErrorDefinition definition = LegacyErrorCodeMapper.fromLegacy(errorCode);
        if (definition.category() == ErrorCategory.SYSTEM || definition.category() == ErrorCategory.DEPENDENCY) {
            return definition.defaultUserMessage();
        }
        return message == null || message.isBlank() ? definition.defaultUserMessage() : message;
    }
}
