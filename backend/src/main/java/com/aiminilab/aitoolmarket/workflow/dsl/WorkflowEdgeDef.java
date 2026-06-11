package com.aiminilab.aitoolmarket.workflow.dsl;

public record WorkflowEdgeDef(
        String id,
        String source,
        String target,
        String sourceHandle,
        String targetHandle
) {
}
