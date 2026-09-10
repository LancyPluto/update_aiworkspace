package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentToolCallRequest(
        @NotBlank(message = "工具编码不能为空")
        String toolCode,
        Object argumentsJson,
        String idempotencyKey
) {
}
