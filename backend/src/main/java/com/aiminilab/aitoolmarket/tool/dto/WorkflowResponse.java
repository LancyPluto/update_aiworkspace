package com.aiminilab.aitoolmarket.tool.dto;

import java.time.LocalDateTime;

public record WorkflowResponse(
        Long id,
        Long toolId,
        String workflowName,
        String nodesJson,
        String edgesJson,
        String groupsJson,
        String configJson,
        int version,
        String status,
        Long createdBy,
        Long updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
