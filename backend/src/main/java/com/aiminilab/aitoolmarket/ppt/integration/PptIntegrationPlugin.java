package com.aiminilab.aitoolmarket.ppt.integration;

import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.integration.IntegrationMode;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationConfig;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PPT 工作台插件：把 {@code tool-integration.ppt} 子对象（或旧版 ppt-workflow JSON）
 * 解析为 {@link PptWorkflow} 暴露给用户详情接口。
 */
@Component
public class PptIntegrationPlugin implements ToolIntegrationPlugin {

    public static final String PLUGIN_ID = "ppt";

    private static final Logger log = LoggerFactory.getLogger(PptIntegrationPlugin.class);

    private final ObjectMapper objectMapper;

    public PptIntegrationPlugin(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String integrationMode() {
        return IntegrationMode.PPT_WORKSPACE;
    }

    @Override
    public String pluginId() {
        return PLUGIN_ID;
    }

    @Override
    public Object userDetailExtension(AiTool tool, ToolIntegrationConfig config) {
        if (config == null) {
            return null;
        }
        JsonNode node = config.extra(PLUGIN_ID);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, PptWorkflow.class);
        } catch (Exception exception) {
            log.warn("PPT workflow 解析失败 toolCode={}: {}",
                    tool == null ? null : tool.getToolCode(),
                    exception.getMessage());
            return null;
        }
    }
}
