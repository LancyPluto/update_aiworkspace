package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BulkUpdateAgentToolAccessRequest(
        @NotEmpty List<String> toolCodes,
        @NotNull Boolean agentEnabled
) {
}
