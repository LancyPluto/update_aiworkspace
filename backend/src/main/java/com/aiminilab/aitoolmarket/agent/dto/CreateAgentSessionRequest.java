package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Size;

public record CreateAgentSessionRequest(
        @Size(max = 120, message = "会话标题过长")
        String title,
        Long workspaceId
) {
}
