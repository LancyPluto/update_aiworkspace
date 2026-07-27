package com.aiminilab.aitoolmarket.ppt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RetryPptJobRequest(
        @NotBlank @Size(max = 128) String clientRequestId
) {
}
