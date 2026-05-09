package com.aiminilab.aitoolmarket.tool.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FieldSchemaResponse(
        Long id,
        Long toolId,
        String schemaVersion,
        String status,
        List<ToolFieldResponse> fields,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
