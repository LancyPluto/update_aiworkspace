package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpsertWorkflowRequest(
        @NotBlank String workflowName,
        @NotBlank String nodesJson,
        @NotBlank String edgesJson,
        String groupsJson,
        String configJson,
        String status,
        @NotNull @PositiveOrZero Long expectedDraftRevision
) {}
