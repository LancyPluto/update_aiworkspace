package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;

public record ToolCategoryResponse(
        Long id,
        String categoryCode,
        String categoryName,
        Integer sortOrder,
        String status
) {
    public static ToolCategoryResponse from(ToolCategory category) {
        return new ToolCategoryResponse(
                category.getId(),
                category.getCategoryCode(),
                category.getCategoryName(),
                category.getSortOrder(),
                category.getStatus()
        );
    }
}
