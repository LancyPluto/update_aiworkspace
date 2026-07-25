package com.aiminilab.aitoolmarket.ppt.engine;

import com.fasterxml.jackson.databind.JsonNode;

public record EngineSubmission(
        EngineJobState state,
        String externalJobId,
        int progress,
        String message,
        JsonNode result
) {
}
