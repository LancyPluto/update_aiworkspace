package com.aiminilab.aitoolmarket.ppt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePptWorkspaceProjectRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 10000) String topic,
        @Size(max = 32) String creationType,
        @Size(max = 16) String language,
        @Size(max = 16) String aspectRatio,
        @Min(1) @Max(100) Integer pageCount,
        @Size(max = 128) String toolCode,
        Long textModelConfigId,
        Long imageModelConfigId
) {
}
