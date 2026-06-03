package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentFile;

import java.time.LocalDateTime;

public record AgentFileResponse(
        Long id,
        Long sessionId,
        String originalFilename,
        String contentType,
        Long fileSize,
        String downloadUrl,
        String status,
        String extractedText,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentFileResponse from(AgentFile file) {
        String downloadUrl = file == null ? null : "/api/v1/agent/sessions/" + file.getSessionId() + "/files/" + file.getId() + "/content";
        return new AgentFileResponse(
                file.getId(),
                file.getSessionId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getFileSize(),
                downloadUrl,
                file.getStatus(),
                file.getExtractedText(),
                file.getErrorMessage(),
                file.getCreatedAt(),
                file.getUpdatedAt()
        );
    }
}
