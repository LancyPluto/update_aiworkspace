package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record SearchLangGraphCheckpointsRequest(@NotBlank String threadId, String checkpointNs,
    String beforeCheckpointId, Integer limit, Map<String, Object> metadataFilter) {}
