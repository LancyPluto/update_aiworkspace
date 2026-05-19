package com.aiminilab.aitoolmarket.agent.dto;

public record InternalCreateWorkspaceMemoryRequest(
        String memoryType,
        String title,
        String content,
        Long sourceRunId,
        Long userId
) {
}
