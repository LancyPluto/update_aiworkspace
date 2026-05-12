package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record FailAgentToolCallRequest(
        @NotBlank(message = "错误码不能为空")
        String errorCode,
        @NotBlank(message = "错误信息不能为空")
        String errorMessage
) {
}
