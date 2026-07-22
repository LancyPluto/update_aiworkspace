package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record PublicToolFrontendStyleResponse(
        String primaryColor,
        String welcomeMessage,
        String mediaDisplayMode,
        String modelIconUrl,
        String comparisonOriginalUrl,
        String comparisonEffectUrl,
        String audioPreviewUrl,
        String heroTitle,
        String heroSubtitle,
        List<String> demoThumbnails,
        List<String> useCases,
        List<String> steps,
        List<String> recommendedToolCodes,
        String beforeVideoUrl,
        String afterVideoUrl
) {
    public static PublicToolFrontendStyleResponse summaryFrom(ToolFrontendStyleConfig style) {
        if (style == null) {
            return null;
        }
        return new PublicToolFrontendStyleResponse(
                style.primaryColor(),
                null,
                style.mediaDisplayMode(),
                style.modelIconUrl(),
                style.comparisonOriginalUrl(),
                style.comparisonEffectUrl(),
                null,
                null,
                style.heroSubtitle(),
                firstItem(style.demoThumbnails()),
                null,
                null,
                null,
                null,
                null
        );
    }

    public static PublicToolFrontendStyleResponse detailFrom(ToolFrontendStyleConfig style) {
        if (style == null) {
            return null;
        }
        return new PublicToolFrontendStyleResponse(
                style.primaryColor(),
                style.welcomeMessage(),
                style.mediaDisplayMode(),
                style.modelIconUrl(),
                style.comparisonOriginalUrl(),
                style.comparisonEffectUrl(),
                style.audioPreviewUrl(),
                style.heroTitle(),
                style.heroSubtitle(),
                style.demoThumbnails(),
                style.useCases(),
                style.steps(),
                style.recommendedToolCodes(),
                style.beforeVideoUrl(),
                style.afterVideoUrl()
        );
    }

    private static List<String> firstItem(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.of(values.get(0));
    }
}
