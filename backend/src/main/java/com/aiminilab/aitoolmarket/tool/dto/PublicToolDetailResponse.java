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
        Long defaultModelConfigId,
        List<ToolSupportedModelResponse> supportedModels,
        List<PublicToolFieldResponse> fields,
        PublicToolFrontendStyleResponse frontendStyle
) {
    public static PublicToolDetailResponse of(
            PublicToolCompactResponse compact,
            List<PublicToolFieldResponse> fields,
            PublicToolFrontendStyleResponse frontendStyle
    ) {
        return of(compact, fields, frontendStyle, null, List.of());
    }

    public static PublicToolDetailResponse of(
            PublicToolCompactResponse compact,
            List<PublicToolFieldResponse> fields,
            PublicToolFrontendStyleResponse frontendStyle,
            Long defaultModelConfigId,
            List<ToolSupportedModelResponse> supportedModels
    ) {
        return new PublicToolDetailResponse(
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
                defaultModelConfigId,
                supportedModels == null ? List.of() : supportedModels,
                fields,
                frontendStyle
        );
    }
}
