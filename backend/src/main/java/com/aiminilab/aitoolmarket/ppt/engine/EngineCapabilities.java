package com.aiminilab.aitoolmarket.ppt.engine;

import java.util.Set;

public record EngineCapabilities(
        String engineCode,
        String displayName,
        boolean available,
        boolean visualGeneration,
        boolean nativeEditablePptx,
        boolean cancellation,
        Set<String> supportedJobs
) {
}
