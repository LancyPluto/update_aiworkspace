package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertAgentGraphCheckpointRequest(
        @NotBlank(message = "checkpoint 不能为空")
        String checkpointJson
) {
}
