package com.aiminilab.aitoolmarket.tool.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFrontendStyleConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesPolloStylePublicDisplayConfigFromConfigNote() {
        String configNote = """
                operator note

                <!-- ai-tool-ui:{"primaryColor":"#ff2f6d","mediaDisplayMode":"comparison","heroTitle":"一键商品图","heroSubtitle":"上传图片，生成高级商品主图","comparisonOriginalUrl":"/generated/original.png","comparisonEffectUrl":"/generated/effect.png","demoThumbnails":["/generated/a.png","/generated/b.png"],"useCases":["商品图","头像"],"steps":["上传图片","AI 处理","下载结果"],"recommendedToolCodes":["style_transfer"]} -->
                """;

        ToolFrontendStyleConfig style = ToolFrontendStyleConfig.fromConfigNote(configNote, objectMapper);

        assertEquals("#ff2f6d", style.primaryColor());
        assertEquals("comparison", style.mediaDisplayMode());
        assertEquals("一键商品图", style.heroTitle());
        assertEquals("上传图片，生成高级商品主图", style.heroSubtitle());
        assertEquals("/generated/original.png", style.comparisonOriginalUrl());
        assertEquals("/generated/effect.png", style.comparisonEffectUrl());
        assertEquals(2, style.demoThumbnails().size());
        assertEquals("商品图", style.useCases().get(0));
        assertEquals("AI 处理", style.steps().get(1));
        assertEquals("style_transfer", style.recommendedToolCodes().get(0));
    }

    @Test
    void fallsBackSafelyWhenConfigNoteJsonIsInvalid() {
        ToolFrontendStyleConfig style = ToolFrontendStyleConfig.fromConfigNote(
                "<!-- ai-tool-ui:{not json} -->",
                objectMapper
        );

        assertEquals("#3b82f6", style.primaryColor());
        assertEquals("icon", style.mediaDisplayMode());
        assertTrue(style.demoThumbnails().isEmpty());
        assertFalse(style.hasComparisonImages());
    }
}
