package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertToolCategoryRequest(
        @NotBlank String categoryCode,
        @NotBlank String categoryName,
        Integer sortOrder,
        String status
) {
}
