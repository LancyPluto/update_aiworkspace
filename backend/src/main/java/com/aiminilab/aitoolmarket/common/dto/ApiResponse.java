package com.aiminilab.aitoolmarket.common.dto;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String requestId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.name(), "ok", data, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.name(), message, null, null);
    }
}
