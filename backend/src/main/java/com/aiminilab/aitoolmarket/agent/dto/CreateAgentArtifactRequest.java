package com.aiminilab.aitoolmarket.agent.dto;

public record CreateAgentArtifactRequest(
        String filename,
        String content,
        String contentType
) {
}
