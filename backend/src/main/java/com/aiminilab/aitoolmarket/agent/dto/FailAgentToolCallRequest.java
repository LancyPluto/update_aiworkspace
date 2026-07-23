package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record FailAgentToolCallRequest(
        @NotBlank(message = "错误码不能为空")
        String errorCode,
        @NotBlank(message = "错误信息不能为空")
        String errorMessage,
        String userMessage,
        String developerMessage,
        String failureTraceId
) {
    public FailAgentToolCallRequest(String errorCode, String errorMessage) {
        this(errorCode, errorMessage, null, null, null);
    }
}
