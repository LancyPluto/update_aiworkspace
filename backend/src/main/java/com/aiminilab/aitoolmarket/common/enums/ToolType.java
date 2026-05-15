package com.aiminilab.aitoolmarket.common.enums;

import java.util.Arrays;

public enum ToolType {
    TEXT_GENERATION,
    IMAGE_GENERATION,
    IMAGE_TO_IMAGE,
    IMAGE_UNDERSTANDING,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    VIDEO_GENERATION,
    EMBEDDING,
    RERANK,
    AGENT;

    public static ToolType fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return TEXT_GENERATION;
        }
        String normalized = value.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElse(TEXT_GENERATION);
    }
}
