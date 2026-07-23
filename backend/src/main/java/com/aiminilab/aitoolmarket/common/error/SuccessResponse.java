package com.aiminilab.aitoolmarket.common.error;

public record SuccessResponse<T>(
        String code,
        T data,
        String traceId
) {
}
