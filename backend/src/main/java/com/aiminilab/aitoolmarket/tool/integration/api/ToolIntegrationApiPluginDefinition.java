package com.aiminilab.aitoolmarket.tool.integration.api;

import java.util.List;

public record ToolIntegrationApiPluginDefinition(
        String pluginId,
        String displayName,
        String syncHint,
        List<ToolModelBindingDefinition> modelBindings,
        List<ToolEngineApiFieldDefinition> engineApiFields
) {
}
