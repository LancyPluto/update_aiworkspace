package com.aiminilab.aitoolmarket.agent.dto;

public record InternalAgentFileChunkContextResponse(
        Long id,
        Long fileId,
        String originalFilename,
        Integer chunkIndex,
        String contentText,
        String metadataJson,
        Integer score
) {
}
