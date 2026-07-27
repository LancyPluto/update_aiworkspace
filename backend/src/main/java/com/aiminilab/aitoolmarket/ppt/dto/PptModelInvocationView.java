package com.aiminilab.aitoolmarket.ppt.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record PptModelInvocationView(
        Long invocationId,
        String status,
        Integer progress,
        String progressMessage,
        JsonNode result,
        String errorCode,
        String errorMessage,
        String pollUrl
) {}
