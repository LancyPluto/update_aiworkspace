package com.aiminilab.aitoolmarket.common.error;

public record UserErrorResponse(
        String errorCode,
        String userMessage,
        String traceId
) {
}
