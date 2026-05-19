package com.aiminilab.aitoolmarket.common.enums;

import java.util.Arrays;

public enum ToolModality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    JSON,
    FILE,
    MULTIMODAL;

    public static ToolModality fromNullable(String value, ToolModality fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElse(fallback);
    }
}
