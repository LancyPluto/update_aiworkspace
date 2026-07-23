package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;

import java.util.Map;

public class BizException extends AppException {

    public BizException(ErrorDefinition errorDefinition) {
        this(errorDefinition, errorDefinition.defaultUserMessage(), errorDefinition.code() + " business rule rejected the request");
    }

    public BizException(ErrorDefinition errorDefinition, String userMessage, String developerMessage) {
        this(errorDefinition, userMessage, developerMessage, null, Map.of());
    }

    public BizException(ErrorDefinition errorDefinition,
                        String userMessage,
                        String developerMessage,
                        Throwable cause,
                        Map<String, Object> logContext) {
        super(errorDefinition, userMessage, developerMessage, cause, logContext);
    }
}
