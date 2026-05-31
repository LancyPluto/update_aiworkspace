package com.aiminilab.aitoolmarket.agent.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record InternalRecentToolCallContextResponse(
        Long id,
        Long runId,
        String toolCode,
        Long taskId,
        Map<String, Object> argumentsJson,
        Map<String, Object> resultJson,
        String resourceType,
        List<String> mediaUrls,
        LocalDateTime createdAt
) {
}
