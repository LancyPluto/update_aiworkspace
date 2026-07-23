package com.aiminilab.aitoolmarket.tool.dto;

import java.util.List;

public record ToolSupportedModelResponse(
        Long modelConfigId,
        String displayName,
        String provider,
        List<String> capabilities,
        String contractStatus,
        String requestSchemaJson,
        Boolean isDefault
) {
}
