package com.aiminilab.aitoolmarket.ppt.engine;

import com.fasterxml.jackson.databind.JsonNode;

public record EngineJobSnapshot(
        EngineJobState state,
        int progress,
        String message,
        String errorCode,
        String errorMessage,
        boolean retryable,
        JsonNode result
) {
}
