package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;

import java.util.Map;

public class DependencyException extends AppException {

    public DependencyException(ErrorDefinition errorDefinition, String developerMessage) {
        this(errorDefinition, developerMessage, null, Map.of());
    }

    public DependencyException(ErrorDefinition errorDefinition,
                               String developerMessage,
                               Throwable cause,
                               Map<String, Object> logContext) {
        super(errorDefinition, errorDefinition.defaultUserMessage(), developerMessage, cause, logContext);
    }
}
