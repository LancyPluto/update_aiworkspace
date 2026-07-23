package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;

import java.util.Map;
import java.util.Objects;

public abstract class AppException extends RuntimeException {

    private final ErrorDefinition errorDefinition;
    private final String userMessage;
    private final String developerMessage;
    private final Map<String, Object> logContext;

    protected AppException(ErrorDefinition errorDefinition,
                           String userMessage,
                           String developerMessage,
                           Throwable cause,
                           Map<String, Object> logContext) {
        super(resolveDeveloperMessage(errorDefinition, developerMessage), cause);
        this.errorDefinition = Objects.requireNonNull(errorDefinition, "errorDefinition");
        this.userMessage = userMessage == null || userMessage.isBlank()
                ? errorDefinition.defaultUserMessage()
                : userMessage;
        this.developerMessage = resolveDeveloperMessage(errorDefinition, developerMessage);
        this.logContext = logContext == null ? Map.of() : Map.copyOf(logContext);
    }

    public ErrorDefinition getErrorDefinition() {
        return errorDefinition;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getDeveloperMessage() {
        return developerMessage;
    }

    public Map<String, Object> getLogContext() {
        return logContext;
    }

    private static String resolveDeveloperMessage(ErrorDefinition definition, String developerMessage) {
        Objects.requireNonNull(definition, "errorDefinition");
        return developerMessage == null || developerMessage.isBlank()
                ? definition.code() + " occurred"
                : developerMessage;
    }
}
