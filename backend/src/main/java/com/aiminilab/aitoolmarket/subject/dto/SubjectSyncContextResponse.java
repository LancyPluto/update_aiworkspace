package com.aiminilab.aitoolmarket.subject.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record SubjectSyncContextResponse(
        String subjectCode,
        Long userId,
        String displayName,
        String description,
        String providerCode,
        String vendorAccountRef,
        String referenceType,
        JsonNode referenceJson,
        String baseUrl,
        String apiKey,
        String extraAuthJson
) {
}
