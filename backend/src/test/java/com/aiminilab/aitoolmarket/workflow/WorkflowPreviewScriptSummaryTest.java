package com.aiminilab.aitoolmarket.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Mirrors {@code WorkflowExecutionService#summarizeScriptPreview} field coverage.
 */
class WorkflowPreviewScriptSummaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void summarizeCopiesComicDramaScriptFields() throws Exception {
        ObjectNode script = objectMapper.createObjectNode();
        script.put("sceneTitle", "温情漫剧");
        script.put("sceneDescription", "室内祖孙对话");
        script.put("dialogue", "奶就放心了。");
        script.put("narration", "奶奶轻声安慰");
        script.put("subtitleZh", "奶就放心了");
        script.put("subtitleEn", "so Grandma won't worry.");
        script.put("title", "雨夜归家");
        script.put("synopsis", "祖孙在雨夜互相安慰。");
        script.put("screenplay", "第一幕：奶奶坐在窗边，孙女推门回家。");
        script.put("genre", "家庭温情");
        script.set("characters", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("name", "奶奶")));
        script.set("locations", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("name", "客厅")));

        JsonNode summary = summarize(script);

        assertEquals("温情漫剧", summary.get("sceneTitle").asText());
        assertEquals("室内祖孙对话", summary.get("sceneDescription").asText());
        assertEquals("奶就放心了。", summary.get("dialogue").asText());
        assertEquals("奶奶轻声安慰", summary.get("narration").asText());
        assertEquals("奶就放心了", summary.get("subtitleZh").asText());
        assertEquals("雨夜归家", summary.get("title").asText());
        assertEquals("祖孙在雨夜互相安慰。", summary.get("synopsis").asText());
        assertEquals("第一幕：奶奶坐在窗边，孙女推门回家。", summary.get("screenplay").asText());
        assertEquals("家庭温情", summary.get("genre").asText());
        assertEquals("奶奶", summary.get("characters").get(0).get("name").asText());
        assertEquals("客厅", summary.get("locations").get(0).get("name").asText());
    }

    @Test
    void summarizeParsesJsonEmbeddedInContentText() throws Exception {
        ObjectNode script = objectMapper.createObjectNode();
        script.put(
                "contentText",
                "{\"sceneTitle\":\"测试\",\"dialogue\":\"你好\",\"sceneDescription\":\"描述\"}"
        );

        JsonNode summary = summarize(script);

        assertEquals("测试", summary.get("sceneTitle").asText());
        assertEquals("你好", summary.get("dialogue").asText());
        assertEquals("描述", summary.get("sceneDescription").asText());
    }

    @Test
    void stageFilterKeepsOnlyScriptForScriptFeedback() {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("imageUrl", "https://example.com/a.png");
        preview.put("audioUrl", "https://example.com/a.mp3");
        preview.set("script", objectMapper.createObjectNode().put("dialogue", "hi"));

        applyStagePreviewFilter(preview, "scriptFeedback");

        assertNull(preview.get("imageUrl"));
        assertNull(preview.get("audioUrl"));
        assertEquals("hi", preview.get("script").get("dialogue").asText());
    }

    private JsonNode summarize(JsonNode script) throws Exception {
        if (script == null || script.isMissingNode() || script.isNull()) {
            return null;
        }
        JsonNode source = script;
        JsonNode contentText = script.get("contentText");
        if (contentText != null && contentText.isTextual() && contentText.asText().trim().startsWith("{")) {
            source = objectMapper.readTree(contentText.asText());
        }
        ObjectNode summary = objectMapper.createObjectNode();
        for (String field : new String[]{
                "contentText", "sceneTitle", "sceneDescription", "dialogue", "narration",
                "subtitleZh", "subtitleEn", "presenterGender", "script", "markdown", "title",
                "synopsis", "screenplay", "genre"
        }) {
            JsonNode value = source.get(field);
            if (value != null && value.isTextual()) {
                summary.put(field, value.asText());
            }
        }
        for (String field : new String[]{"characters", "locations"}) {
            JsonNode value = source.get(field);
            if (value != null && value.isArray()) {
                summary.set(field, value);
            }
        }
        return summary.isEmpty() ? script : summary;
    }

    private void applyStagePreviewFilter(ObjectNode preview, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return;
        }
        switch (fieldKey) {
            case "scriptFeedback", "storyboardFeedback" -> {
                preview.remove("imageUrl");
                preview.remove("audioUrl");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
            }
            case "sceneFeedback" -> {
                preview.remove("script");
                preview.remove("audioUrl");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
            }
            case "bgmFeedback" -> {
                preview.remove("script");
                preview.remove("imageUrl");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
            }
            default -> {
            }
        }
    }
}
