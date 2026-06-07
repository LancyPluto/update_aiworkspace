package com.aiminilab.aitoolmarket.agent.dto;

public record AdminAgentFileSnapshotResponse(
        Long id,
        String originalFilename,
        String contentType,
        String status,
        String downloadUrl
) {
    public static AdminAgentFileSnapshotResponse from(InternalAgentFileContextResponse file) {
        return new AdminAgentFileSnapshotResponse(
                file.id(),
                file.originalFilename(),
                file.contentType(),
                file.status(),
                file.downloadUrl()
        );
    }
}
