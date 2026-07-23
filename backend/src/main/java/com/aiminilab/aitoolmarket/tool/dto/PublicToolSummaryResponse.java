package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

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
        PublicToolCardMediaResponse cardMedia
) {
    public static PublicToolSummaryResponse from(
            PublicToolCompactResponse compact,
            ToolFrontendStyleConfig frontendStyle
    ) {
        return new PublicToolSummaryResponse(
                compact.toolCode(),
                compact.toolName(),
                compact.categoryCode(),
                compact.categoryName(),
                compact.description(),
                compact.coverUrl(),
                compact.toolType(),
                compact.inputModality(),
                compact.outputModality(),
                compact.toolKind(),
                compact.estimatedCreditCost(),
                compact.variableCreditPricing(),
                compact.modelDisplayName(),
                PublicToolCardMediaResponse.from(frontendStyle)
        );
    }
}
