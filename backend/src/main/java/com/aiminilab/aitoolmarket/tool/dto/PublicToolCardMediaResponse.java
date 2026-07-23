package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record PublicToolCardMediaResponse(
        String mediaDisplayMode,
        String modelIconUrl,
        String comparisonOriginalUrl,
        String comparisonEffectUrl,
        List<String> demoThumbnails,
        String heroSubtitle
) {
    public static PublicToolCardMediaResponse from(ToolFrontendStyleConfig style) {
        if (style == null) {
            return null;
        }
        return new PublicToolCardMediaResponse(
                style.mediaDisplayMode(),
                style.modelIconUrl(),
                style.comparisonOriginalUrl(),
                style.comparisonEffectUrl(),
                firstItem(style.demoThumbnails()),
                style.heroSubtitle()
        );
    }

    private static List<String> firstItem(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.of(values.get(0));
    }
}
