package com.aiminilab.aitoolmarket.subject.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSubjectRequest(
        @NotBlank @Size(max = 128) String displayName,
        @Size(max = 512) String description,
        @NotBlank String referenceType,
        JsonNode referenceJson,
        String vendorAccountRef
) {
}
