package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record ImageGenerationParametersResponse(
        List<ImageSizeOptionResponse> sizes,
        String defaultSize,
        List<Integer> counts,
        Integer defaultCount,
        List<ImageSizeOptionResponse> qualities,
        String defaultQuality
) {
}
