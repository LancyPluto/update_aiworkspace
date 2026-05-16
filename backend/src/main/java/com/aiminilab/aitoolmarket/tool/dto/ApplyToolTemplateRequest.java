package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record ApplyToolTemplateRequest(
        @NotBlank String templateCode,
        Boolean applyMetadata,
        Boolean overwritePrompt
) {
    public boolean shouldApplyMetadata() {
        return applyMetadata == null || applyMetadata;
    }

    public boolean shouldOverwritePrompt() {
        return overwritePrompt == null || overwritePrompt;
    }
}
