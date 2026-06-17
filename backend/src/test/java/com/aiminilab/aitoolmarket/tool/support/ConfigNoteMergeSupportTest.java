package com.aiminilab.aitoolmarket.tool.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigNoteMergeSupportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Test
    void preservesMediaUrlsWhenIncomingBundleHasEmptyUrls() {
        String existing = """
                <!-- ai-tool-ui:{"primaryColor":"#ff2f6d","mediaDisplayMode":"effect","modelIconUrl":"/oss/icon.png","comparisonOriginalUrl":"","comparisonEffectUrl":"","heroTitle":"旧标题","heroSubtitle":"","demoThumbnails":["/oss/demo.png"],"useCases":[],"steps":[],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->
                """;
        String incoming = """
                <!-- ai-tool-ui:{"primaryColor":"#3b82f6","mediaDisplayMode":"effect","modelIconUrl":"","comparisonOriginalUrl":"","comparisonEffectUrl":"","heroTitle":"新标题","heroSubtitle":"","demoThumbnails":[],"useCases":[],"steps":[],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->
                """;

        String merged = ConfigNoteMergeSupport.mergePreservingMediaUrls(existing, incoming, OBJECT_MAPPER);

        ToolFrontendStyleConfig style = ToolFrontendStyleConfig.fromConfigNote(merged, OBJECT_MAPPER);
        assertEquals("新标题", style.heroTitle());
        assertEquals("/oss/icon.png", style.modelIconUrl());
        assertEquals("/oss/demo.png", style.demoThumbnails().get(0));
    }

    @Test
    void incomingNonBlankUrlOverridesExisting() {
        assertEquals("/new.png", ConfigNoteMergeSupport.preferNonBlankUrl("/old.png", "/new.png"));
        assertEquals("/old.png", ConfigNoteMergeSupport.preferNonBlankUrl("/old.png", ""));
        assertEquals("/old.png", ConfigNoteMergeSupport.preferNonBlankUrl("/old.png", null));
    }
}
