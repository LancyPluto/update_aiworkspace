package com.aiminilab.aitoolmarket.ppt.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PptModelInvocationRequest(
        @NotNull Long projectId,
        @NotNull Long pptJobId,
        @NotBlank String capability,
        @NotBlank String idempotencyKey,
        @NotNull JsonNode input
) {}
