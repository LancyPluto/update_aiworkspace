package com.aiminilab.aitoolmarket.ppt.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitPptJobRequest(
        @NotBlank @Size(max = 64) String jobType,
        @NotBlank @Size(max = 128) String clientRequestId,
        JsonNode payload
) {
}
