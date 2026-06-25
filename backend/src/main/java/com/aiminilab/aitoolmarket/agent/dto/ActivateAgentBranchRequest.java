package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotNull;

public record ActivateAgentBranchRequest(
        @NotNull(message = "锚点消息不能为空")
        Long anchorMessageId,
        @NotNull(message = "分支消息不能为空")
        Long variantMessageId
) {
}
