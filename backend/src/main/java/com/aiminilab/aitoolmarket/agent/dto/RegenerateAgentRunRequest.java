package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body for {@code POST /api/v1/agent/runs/{runId}/regenerate}.
 */
public record RegenerateAgentRunRequest(
        @Size(max = 64, message = "clientRequestId 过长")
        String clientRequestId
) {
}
