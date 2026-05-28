package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAgentToolAccessRequest(
        @NotNull Boolean agentEnabled
) {
}
