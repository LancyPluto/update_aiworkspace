package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具详情接口返回的集成块（用户端 / 管理端通用视图）。
 *
 * <p>仅返回前端入口决策所需字段；插件私有结构通过
 * {@link #extension()} 透传，由具体插件 {@code userDetailExtension}
 * 决定形态。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolIntegrationView(
        String integrationMode,
        String pluginId,
        String customUiRoute,
        String apiPrefix,
        String displayName,
        ToolIntegrationConfig.Engine engine,
        Object extension
) {

    public static ToolIntegrationView of(ToolIntegrationConfig config, Object extension) {
        if (config == null) {
            return null;
        }
        return new ToolIntegrationView(
                config.getIntegrationMode(),
                config.getPluginId(),
                config.getCustomUiRoute(),
                config.getApiPrefix(),
                config.getDisplayName(),
                null,
                extension
        );
    }
}
