package com.aiminilab.aitoolmarket.ppt.dto;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;

public record PptAdminWorkflowDetailResponse(
        PptWorkflow workflow,
        AgentModelConfigResponse textModel,
        AgentModelConfigResponse imageModel,
        boolean engineSynced,
        String engineSyncMessage
) {
}
