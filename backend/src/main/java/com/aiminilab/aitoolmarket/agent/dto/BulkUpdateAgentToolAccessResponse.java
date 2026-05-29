package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record BulkUpdateAgentToolAccessResponse(
        List<AdminAgentToolAccessResponse> updatedTools,
        List<FailedToolUpdate> failedToolCodes
) {
    public record FailedToolUpdate(String toolCode, String reason) {
    }
}
