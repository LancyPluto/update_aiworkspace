package com.aiminilab.aitoolmarket.ppt.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePptProjectRequest(
        @NotBlank String creationType,
        String ideaPrompt,
        String outlineText,
        String descriptionText,
        String templateStyle,
        String imageAspectRatio,
        String title,
        String clientRequestId
) {
}
