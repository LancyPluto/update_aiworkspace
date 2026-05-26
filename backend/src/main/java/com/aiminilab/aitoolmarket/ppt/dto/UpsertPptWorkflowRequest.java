package com.aiminilab.aitoolmarket.ppt.dto;

import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpsertPptWorkflowRequest(
        @NotNull @Valid PptWorkflow workflow
) {
}
