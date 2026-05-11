package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AgentFileParseResult(
        String filename,
        String text,
        List<AgentFileParseChunk> chunks
) {
    public static AgentFileParseResult fromText(String filename, String text) {
        return new AgentFileParseResult(filename, text, List.of());
    }
}
