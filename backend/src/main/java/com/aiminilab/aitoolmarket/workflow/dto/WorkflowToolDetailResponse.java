package com.aiminilab.aitoolmarket.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record WorkflowToolDetailResponse(
        Long id,
        String toolCode,
        String toolName,
        String description,
        String categoryName,
        String coverUrl,
        String status,
        Integer estimatedCreditCost,
        Integer minimumRequiredCredits,
        Boolean variableCreditPricing,
        String adapterKey,
        List<Map<String, Object>> fields,
        JsonNode inputSchema,
        Integer workflowVersion,
        Integer estimatedDurationSeconds,
        JsonNode adapterData
) {
}
