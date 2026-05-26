package com.aiminilab.aitoolmarket.tool.integration.api;

/**
 * 绑定超市 {@code agent_model_configs} 的一项（同步到引擎的大模型凭证）。
 */
public record ToolModelBindingDefinition(
        String bindingKey,
        String label,
        String description,
        String requiredCapability,
        String endpointHint
) {
}
