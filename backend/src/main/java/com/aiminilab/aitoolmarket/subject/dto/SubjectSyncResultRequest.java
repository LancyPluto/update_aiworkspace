package com.aiminilab.aitoolmarket.subject.dto;

import jakarta.validation.constraints.NotBlank;

public record SubjectSyncResultRequest(
        @NotBlank String syncStatus,
        String syncTaskId,
        String upstreamElementId,
        String syncError
) {
}
