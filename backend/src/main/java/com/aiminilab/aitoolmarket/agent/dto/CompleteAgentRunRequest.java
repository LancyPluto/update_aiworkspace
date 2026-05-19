package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record CompleteAgentRunRequest(
        @NotBlank(message = "最终回答不能为空")
        String finalAnswer,
        String intent,
        String modelProviderCode,
        String modelName,
        Integer consumedCredits,
        Integer promptTokens,
        Integer completionTokens
) {
}
