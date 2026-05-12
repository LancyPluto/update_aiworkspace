package com.aiminilab.aitoolmarket.agent.dto;

import java.util.Map;

public record AgentFileParseChunk(
        Integer chunkIndex,
        String content,
        Map<String, Object> metadata
) {
}
