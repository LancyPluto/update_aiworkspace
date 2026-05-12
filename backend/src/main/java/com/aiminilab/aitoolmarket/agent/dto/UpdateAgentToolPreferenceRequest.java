package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAgentToolPreferenceRequest(
        @NotNull(message = "自动调用设置不能为空")
        Boolean autoCallEnabled
) {
}
