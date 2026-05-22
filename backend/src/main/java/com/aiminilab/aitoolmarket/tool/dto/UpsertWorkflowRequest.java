package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertWorkflowRequest(
        @NotBlank String workflowName,
        @NotBlank String nodesJson,
        @NotBlank String edgesJson,
        String groupsJson,
        String configJson,
        String status
) {}
