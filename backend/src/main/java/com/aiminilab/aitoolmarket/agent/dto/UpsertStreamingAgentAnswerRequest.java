package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertStreamingAgentAnswerRequest(
        @NotBlank(message = "流式正文不能为空")
        String contentText,
        String contentJson
) {
}
