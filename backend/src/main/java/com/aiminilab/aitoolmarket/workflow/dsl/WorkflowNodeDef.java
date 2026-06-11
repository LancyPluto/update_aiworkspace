package com.aiminilab.aitoolmarket.workflow.dsl;

import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowNodeDef(
        String id,
        WorkflowNodeDefType type,
        String title,
        JsonNode parameters
) {
}
