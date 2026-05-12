package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentRunEventRequest(
        @NotBlank(message = "事件类型不能为空")
        String eventType,
        String eventText,
        Object eventJson
) {
}
