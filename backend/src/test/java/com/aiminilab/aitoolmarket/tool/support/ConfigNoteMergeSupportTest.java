package com.aiminilab.aitoolmarket.tool.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigNoteMergeSupportTest {

    private static final String WORKFLOW = """
            <!-- ppt-workflow:{"textModelConfigId":12,"steps":[{"code":"CREATE","name":"创建","credits":5,"enabled":true}]} -->
            """;

    @Test
    void preservesWorkflowWhenIncomingOmitsMarker() {
        String existing = "运营说明\n" + WORKFLOW.trim();
        String incoming = "运营说明更新\n\n<!-- ai-tool-ui:{\"primaryColor\":\"#3b82f6\"} -->";
        String merged = ConfigNoteMergeSupport.mergePreservingIntegrationMarkers(existing, incoming);
        assertTrue(merged.contains("textModelConfigId"));
        assertTrue(merged.contains("运营说明更新"));
        assertTrue(merged.contains("ai-tool-ui"));
    }

    @Test
    void trustsIncomingWhenItAlreadyContainsMarker() {
        String existing = WORKFLOW.trim();
        String incoming = """
                note
                <!-- ppt-workflow:{"textModelConfigId":99,"steps":[{"code":"CREATE","name":"创建","credits":5,"enabled":true}]} -->
                """;
        String merged = ConfigNoteMergeSupport.mergePreservingIntegrationMarkers(existing, incoming);
        assertTrue(merged.contains("textModelConfigId\":99"));
        assertFalse(merged.contains("textModelConfigId\":12"));
    }
}
