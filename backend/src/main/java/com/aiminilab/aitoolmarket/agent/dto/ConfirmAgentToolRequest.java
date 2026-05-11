package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmAgentToolRequest(
        @NotBlank(message = "toolCode is required")
        String toolCode,
        Boolean approved,
        Boolean autoCallEnabled
) {
}
