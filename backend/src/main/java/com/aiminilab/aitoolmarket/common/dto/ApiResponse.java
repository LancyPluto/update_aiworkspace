package com.aiminilab.aitoolmarket.common.dto;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import org.slf4j.MDC;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.name(), "ok", data, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    @SuppressWarnings("unchecked")
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, Object data) {
        return new ApiResponse<>(errorCode.name(), message, (T) data, MDC.get("traceId"));
    }
}
