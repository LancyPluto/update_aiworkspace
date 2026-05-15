package com.aiminilab.aitoolmarket.commerce.dto;

import jakarta.validation.constraints.NotBlank;

public record ReportModelIssueRequest(
        Long poolId,
        Long nodeId,
        @NotBlank String message
) {
}
