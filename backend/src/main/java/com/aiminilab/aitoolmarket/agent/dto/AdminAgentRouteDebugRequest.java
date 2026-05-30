package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminAgentRouteDebugRequest(
        @NotBlank String message
) {
}
