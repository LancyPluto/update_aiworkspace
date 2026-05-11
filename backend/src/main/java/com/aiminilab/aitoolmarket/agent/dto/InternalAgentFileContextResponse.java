package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentFile;

public record InternalAgentFileContextResponse(
        Long id,
        String originalFilename,
        String contentType,
        String status,
        String extractedText
) {
    private static final int CONTEXT_TEXT_LIMIT = 12_000;

    public static InternalAgentFileContextResponse from(AgentFile file) {
        String text = file.getExtractedText();
        if (text != null && text.length() > CONTEXT_TEXT_LIMIT) {
            text = text.substring(0, CONTEXT_TEXT_LIMIT);
        }
        return new InternalAgentFileContextResponse(
                file.getId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getStatus(),
                text
        );
    }
}
