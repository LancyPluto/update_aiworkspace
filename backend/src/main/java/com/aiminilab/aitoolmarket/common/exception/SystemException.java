package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;

import java.util.Map;

public class SystemException extends AppException {

    public SystemException(ErrorDefinition errorDefinition, String developerMessage) {
        this(errorDefinition, developerMessage, null, Map.of());
    }

    public SystemException(ErrorDefinition errorDefinition,
                           String developerMessage,
                           Throwable cause,
                           Map<String, Object> logContext) {
        super(errorDefinition, errorDefinition.defaultUserMessage(), developerMessage, cause, logContext);
    }
}
