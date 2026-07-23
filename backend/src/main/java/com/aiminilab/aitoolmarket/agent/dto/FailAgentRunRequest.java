package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record FailAgentRunRequest(
        @NotBlank(message = "错误码不能为空")
        String errorCode,
        @NotBlank(message = "错误信息不能为空")
        String errorMessage,
        Integer consumedCredits,
        Integer promptTokens,
        Integer completionTokens,
        String userMessage,
        String developerMessage,
        String failureTraceId
) {
    public FailAgentRunRequest(String errorCode, String errorMessage) {
        this(errorCode, errorMessage, null, null, null, null, null, null);
    }

    public FailAgentRunRequest(String errorCode,
                               String errorMessage,
                               Integer consumedCredits,
                               Integer promptTokens,
                               Integer completionTokens) {
        this(errorCode, errorMessage, consumedCredits, promptTokens, completionTokens, null, null, null);
    }
}
