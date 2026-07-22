package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.aiminilab.aitoolmarket.tool.support.ToolKindSupport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicToolSummaryResponse(
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
        String modelDisplayName,
        PublicToolFrontendStyleResponse frontendStyle
) {
    public static PublicToolSummaryResponse from(
            AiTool tool,
            String publicCoverUrl,
            Integer estimatedCreditCost,
            boolean variableCreditPricing,
            ObjectMapper objectMapper
    ) {
        return new PublicToolSummaryResponse(
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
                tool.getModelDisplayName(),
                PublicToolFrontendStyleResponse.summaryFrom(
                        ToolFrontendStyleConfig.fromConfigNote(tool.getConfigNote(), objectMapper))
        );
    }
}
