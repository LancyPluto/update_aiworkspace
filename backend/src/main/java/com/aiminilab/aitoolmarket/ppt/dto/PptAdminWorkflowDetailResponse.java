package com.aiminilab.aitoolmarket.ppt.dto;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiPluginDefinition;

import java.util.List;

public record PptAdminWorkflowDetailResponse(
        PptWorkflow workflow,
        ToolIntegrationApiPluginDefinition apiCatalog,
        AgentModelConfigResponse textModel,
        AgentModelConfigResponse imageModel,
        List<ToolEngineSecretFieldView> engineSecretFields,
        boolean engineSynced,
        String engineSyncMessage
) {
}
