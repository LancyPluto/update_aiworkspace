package com.aiminilab.aitoolmarket.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record WorkflowConfirmationRequest(
        @NotNull Long stepId,
        @NotBlank String action,
        @NotNull Map<String, Object> fields,
        @NotBlank String confirmationToken,
        @NotBlank String idempotencyKey
) {
}
