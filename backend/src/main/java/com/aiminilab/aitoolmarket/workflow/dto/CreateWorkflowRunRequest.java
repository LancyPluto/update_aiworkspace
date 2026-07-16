package com.aiminilab.aitoolmarket.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkflowRunRequest(
        @NotNull JsonNode input,
        @NotBlank @Size(max = 128) String clientRequestId
) {
}
