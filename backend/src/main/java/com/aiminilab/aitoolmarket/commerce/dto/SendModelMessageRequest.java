package com.aiminilab.aitoolmarket.commerce.dto;

import jakarta.validation.constraints.NotBlank;

public record SendModelMessageRequest(
        @NotBlank String content,
        Long nodeId
) {
}
