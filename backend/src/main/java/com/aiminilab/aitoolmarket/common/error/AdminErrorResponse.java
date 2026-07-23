package com.aiminilab.aitoolmarket.common.error;

public record AdminErrorResponse(
        String errorCode,
        String developerMessage,
        String traceId
) {
}
