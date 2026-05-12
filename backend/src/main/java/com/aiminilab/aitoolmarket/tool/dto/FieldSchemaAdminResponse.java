package com.aiminilab.aitoolmarket.tool.dto;

import java.util.List;

public record FieldSchemaAdminResponse(
        Long id,
        String schemaVersion,
        String status,
        List<ToolFieldResponse> items
) {
}
