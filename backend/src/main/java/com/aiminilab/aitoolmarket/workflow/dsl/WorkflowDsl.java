package com.aiminilab.aitoolmarket.workflow.dsl;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record WorkflowDsl(
        List<WorkflowNodeDef> nodes,
        List<WorkflowEdgeDef> edges,
        JsonNode config,
        List<String> executionOrder
) {
    public Map<String, WorkflowNodeDef> nodeById() {
        return nodes.stream().collect(java.util.stream.Collectors.toMap(WorkflowNodeDef::id, node -> node, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    public WorkflowNodeDef requireNode(String nodeId) {
        WorkflowNodeDef node = nodeById().get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Workflow node not found: " + nodeId);
        }
        return node;
    }
}
