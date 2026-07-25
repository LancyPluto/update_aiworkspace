package com.aiminilab.aitoolmarket.ppt.engine;

public record EngineProjectRequest(
        String title,
        String topic,
        String creationType,
        String language,
        String aspectRatio,
        Integer pageCount,
        Long platformProjectId,
        String idempotencyKey
) {
    public EngineProjectRequest(String title,
                                String topic,
                                String creationType,
                                String language,
                                String aspectRatio,
                                Integer pageCount) {
        this(title, topic, creationType, language, aspectRatio, pageCount, null, null);
    }
}
