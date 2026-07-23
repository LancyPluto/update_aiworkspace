package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.support.ToolKindSupport;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicToolCompactResponse(
        String toolCode,
        String toolName,
        String categoryCode,
        String categoryName,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String toolKind,
        Integer estimatedCreditCost,
        Boolean variableCreditPricing,
        String modelDisplayName
) {
    public static PublicToolCompactResponse from(
            AiTool tool,
            String publicCoverUrl,
            Integer estimatedCreditCost,
            boolean variableCreditPricing
    ) {
        return new PublicToolCompactResponse(
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryCode(),
                tool.getCategoryName(),
                tool.getDescription(),
                publicCoverUrl,
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                ToolKindSupport.resolve(tool),
                variableCreditPricing ? null : estimatedCreditCost,
                variableCreditPricing,
                tool.getModelDisplayName()
        );
    }
}
