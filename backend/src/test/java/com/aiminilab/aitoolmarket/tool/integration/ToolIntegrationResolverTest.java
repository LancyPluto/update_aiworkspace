package com.aiminilab.aitoolmarket.tool.integration;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolIntegrationResolverTest {

    private final ToolIntegrationResolver resolver = new ToolIntegrationResolver(new ObjectMapper());

    @Test
    void parsesPlatformIntegrationBlock() {
        AiTool tool = tool("acme_ppt_pro", """
                运营说明
                <!-- tool-integration:{"integrationMode":"PPT_WORKSPACE","pluginId":"ppt","apiPrefix":"/api/v1/ppt","engine":{"type":"banana-slides","baseUrl":"http://127.0.0.1:5001"},"ppt":{"creationTypes":["idea"],"steps":[{"code":"CREATE","name":"创建","credits":5,"enabled":true}]}} -->
                """);

        ToolIntegrationConfig config = resolver.resolve(tool);

        assertEquals("PPT_WORKSPACE", config.getIntegrationMode());
        assertEquals("ppt", config.getPluginId());
        assertEquals("/api/v1/ppt", config.getApiPrefix());
        assertEquals("/tools/acme_ppt_pro/workspace", config.getCustomUiRoute());
        assertNotNull(config.getEngine());
        assertEquals("http://127.0.0.1:5001", config.getEngine().getBaseUrl());
        assertNotNull(config.extra("ppt"));
    }

    @Test
    void inheritsCustomUiRouteWhenProvided() {
        AiTool tool = tool("acme_ppt_pro", """
                <!-- tool-integration:{"integrationMode":"PPT_WORKSPACE","pluginId":"ppt","customUiRoute":"/studio/ppt"} -->
                """);

        assertEquals("/studio/ppt", resolver.resolve(tool).getCustomUiRoute());
    }

    @Test
    void fallsBackToLegacyPptWorkflowBlock() {
        AiTool tool = tool("banana_ppt_generator", """
                运营说明
                <!-- ppt-workflow:{"integrationMode":"PPT_WORKSPACE","customUiRoute":"/tools/banana_ppt_generator/workspace","creationTypes":["idea"],"steps":[{"code":"CREATE","name":"创建","credits":5,"enabled":true}]} -->
                """);

        ToolIntegrationConfig config = resolver.resolve(tool);

        assertEquals("PPT_WORKSPACE", config.getIntegrationMode());
        assertEquals("ppt", config.getPluginId());
        assertEquals("/api/v1/ppt", config.getApiPrefix());
        assertEquals("/tools/banana_ppt_generator/workspace", config.getCustomUiRoute());
        assertNotNull(config.extra("ppt"));
    }

    @Test
    void preservesMigratedWorkspaceRouteFromLegacyPptBlock() {
        AiTool tool = tool("banana_ppt_generator", """
                <!-- ppt-workflow:{"integrationMode":"PPT_WORKSPACE","customUiRoute":"/ppt","apiPrefix":"/api/v2/ppt"} -->
                """);

        ToolIntegrationConfig config = resolver.resolve(tool);

        assertEquals("/ppt", config.getCustomUiRoute());
        assertEquals("/api/v2/ppt", config.getApiPrefix());
    }

    @Test
    void defaultsToStandardTaskWhenNoMarker() {
        ToolIntegrationConfig config = resolver.resolve(tool("plain_tool", "普通文案工具说明"));

        assertEquals("STANDARD_TASK", config.getIntegrationMode());
        assertTrue(config.isStandardTask());
        assertNull(config.getPluginId());
        assertNull(config.getCustomUiRoute());
    }

    @Test
    void handlesNullConfigNoteGracefully() {
        ToolIntegrationConfig config = resolver.resolve(tool("plain_tool", null));
        assertEquals("STANDARD_TASK", config.getIntegrationMode());
    }

    @Test
    void normalizesIntegrationModeToUpperCase() {
        AiTool tool = tool("acme_ppt_pro", """
                <!-- tool-integration:{"integrationMode":"ppt_workspace","pluginId":"ppt"} -->
                """);
        assertEquals("PPT_WORKSPACE", resolver.resolve(tool).getIntegrationMode());
    }

    private AiTool tool(String code, String configNote) {
        AiTool tool = new AiTool();
        tool.setToolCode(code);
        tool.setConfigNote(configNote);
        return tool;
    }
}
