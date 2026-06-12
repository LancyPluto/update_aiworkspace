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

        JsonNode summary = summarize(script);

        assertEquals("温情漫剧", summary.get("sceneTitle").asText());
        assertEquals("室内祖孙对话", summary.get("sceneDescription").asText());
        assertEquals("奶就放心了。", summary.get("dialogue").asText());
        assertEquals("奶奶轻声安慰", summary.get("narration").asText());
        assertEquals("奶就放心了", summary.get("subtitleZh").asText());
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
                "subtitleZh", "subtitleEn", "presenterGender", "script", "markdown", "title"
        }) {
            JsonNode value = source.get(field);
            if (value != null && value.isTextual()) {
                summary.put(field, value.asText());
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
