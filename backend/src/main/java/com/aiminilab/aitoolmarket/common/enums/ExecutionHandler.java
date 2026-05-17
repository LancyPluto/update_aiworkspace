package com.aiminilab.aitoolmarket.common.enums;

import java.util.Arrays;

public enum ExecutionHandler {
    TEXT_GENERATION,
    IMAGE_GENERATION,
    TEXT_TO_SPEECH,
    VIDEO_GENERATION,
    DIGITAL_HUMAN;

    public static ExecutionHandler fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return TEXT_GENERATION;
        }
        String normalized = value.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(handler -> handler.name().equals(normalized))
                .findFirst()
                .orElse(TEXT_GENERATION);
    }
}
