package com.aiminilab.aitoolmarket.tool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicToolDetailResponse(
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
        List<PublicToolFieldResponse> fields,
        PublicToolFrontendStyleResponse frontendStyle
) {
    public static PublicToolDetailResponse of(
            PublicToolSummaryResponse summary,
            List<PublicToolFieldResponse> fields,
            PublicToolFrontendStyleResponse frontendStyle
    ) {
        return new PublicToolDetailResponse(
                summary.toolCode(),
                summary.toolName(),
                summary.categoryCode(),
                summary.categoryName(),
                summary.description(),
                summary.coverUrl(),
                summary.toolType(),
                summary.inputModality(),
                summary.outputModality(),
                summary.toolKind(),
                summary.estimatedCreditCost(),
                summary.variableCreditPricing(),
                summary.modelDisplayName(),
                fields,
                frontendStyle
        );
    }
}
