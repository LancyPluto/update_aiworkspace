package com.aiminilab.aitoolmarket.subject.dto;

import com.aiminilab.aitoolmarket.subject.entity.GenerationSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record SubjectResponse(
        String subjectCode,
        String displayName,
        String description,
        String providerCode,
        String vendorAccountRef,
        String referenceType,
        String previewUrl,
        JsonNode referenceJson,
        String upstreamElementId,
        String syncTaskId,
        String syncStatus,
        String syncError,
        String createdAt,
        String updatedAt
) {
    public static SubjectResponse from(GenerationSubject subject, ObjectMapper objectMapper) {
        JsonNode reference = parseReference(subject.getReferenceJson(), objectMapper);
        return new SubjectResponse(
                subject.getSubjectCode(),
                subject.getDisplayName(),
                subject.getDescription(),
                subject.getProviderCode(),
                subject.getVendorAccountRef(),
                subject.getReferenceType(),
                subject.getPreviewUrl(),
                reference,
                subject.getUpstreamElementId(),
                subject.getSyncTaskId(),
                subject.getSyncStatus(),
                subject.getSyncError(),
                subject.getCreatedAt() == null ? null : subject.getCreatedAt().toString(),
                subject.getUpdatedAt() == null ? null : subject.getUpdatedAt().toString()
        );
    }

    private static JsonNode parseReference(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
