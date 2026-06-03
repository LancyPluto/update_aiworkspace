package com.aiminilab.aitoolmarket.agent.dto;

public record AdminAgentMemoryQuery(
        Long userId,
        Long workspaceId,
        String status,
        String memoryType,
        String keyword,
        Integer pageNo,
        Integer pageSize
) {
}
