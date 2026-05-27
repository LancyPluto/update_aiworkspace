package com.aiminilab.aitoolmarket.ppt.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record PptTaskResponse(
        String taskId,
        String status,
        JsonNode progress,
        String errorMessage
) {
}
