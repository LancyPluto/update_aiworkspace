package com.aiminilab.aitoolmarket.tool.integration.api;

/**
 * 工作台插件在管理端可配置的一项引擎侧 API 参数（写入引擎 PUT /api/settings 等）。
 */
public record ToolEngineApiFieldDefinition(
        String key,
        String label,
        String description,
        ToolApiFieldType fieldType,
        String enginePayloadKey,
        String endpointHint,
        String docUrl
) {
    public ToolEngineApiFieldDefinition {
        if (enginePayloadKey == null || enginePayloadKey.isBlank()) {
            enginePayloadKey = key;
        }
    }
}
