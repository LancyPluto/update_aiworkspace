package com.aiminilab.aitoolmarket.market.enums;

import java.util.Arrays;
import java.util.Optional;

public enum CapabilityType {
    imageGeneration,
    fileReading,
    webSearch,
    codeExecution,
    voiceInput;

    public static Optional<CapabilityType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst();
    }
}
