package com.aiminilab.aitoolmarket.ppt.domain;

import com.fasterxml.jackson.databind.JsonNode;

public final class PptJobChainPolicy {
    private PptJobChainPolicy() {
    }

    public static PptJobType next(PptJobType current, JsonNode request) {
        if (current == null || request == null || !request.path("autoContinue").asBoolean(false)) {
            return null;
        }
        return switch (current) {
            case GENERATE_OUTLINE -> PptJobType.GENERATE_DESCRIPTIONS;
            case GENERATE_DESCRIPTIONS -> PptJobType.GENERATE_IMAGES;
            case GENERATE_IMAGES -> PptJobType.EXPORT_PPTX;
            case EXPORT_PPTX, EXPORT_EDITABLE_PPTX -> null;
        };
    }
}
