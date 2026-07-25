package com.aiminilab.aitoolmarket.ppt.engine;

import com.aiminilab.aitoolmarket.ppt.domain.PptJobType;
import com.fasterxml.jackson.databind.JsonNode;

public record EngineJobRequest(
        PptJobType jobType,
        String externalProjectId,
        JsonNode payload,
        Long platformProjectId,
        Long platformJobId,
        String idempotencyKey,
        String modelGatewayBaseUrl,
        String executionToken
) {
}
