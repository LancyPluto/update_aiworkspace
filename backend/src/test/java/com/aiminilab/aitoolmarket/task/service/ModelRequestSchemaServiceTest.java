package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRequestSchemaServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
    private final ModelRequestSchemaService service = new ModelRequestSchemaService(fieldMapper, objectMapper);

    @Test
    void validatesSelectedModeAndRemovesHiddenAndUnknownParameters() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(9L);
        ToolFieldItem prompt = new ToolFieldItem();
        prompt.setFieldKey("prompt");
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of(prompt));
        AgentModelConfig model = readyModel("""
                {"version":1,"fields":[
                  {"key":"generationMode","type":"string","required":true,"enum":["text","reference"]},
                  {"key":"referenceImages","type":"array","minItems":1,"maxItems":9,
                   "visibleWhen":{"field":"generationMode","value":"reference"}},
                  {"key":"duration","type":"integer","min":4,"max":15}
                ]}
                """);
        ObjectNode params = (ObjectNode) objectMapper.readTree("""
                {"generationMode":"text","duration":5,"referenceImages":["hidden.png"],
                 "prompt":"keep tool field","injected":"drop"}
                """);

        ObjectNode result = (ObjectNode) service.validateAndSanitize(tool, model, params);

        assertThat(result.has("referenceImages")).isFalse();
        assertThat(result.has("injected")).isFalse();
        assertThat(result.path("prompt").asText()).isEqualTo("keep tool field");
    }

    @Test
    void rejectsRequiredEnumAndRangeViolations() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(10L);
        when(fieldMapper.findActiveFields(10L)).thenReturn(List.of());
        AgentModelConfig model = readyModel("""
                {"version":1,"fields":[
                  {"key":"generationMode","type":"string","required":true,"enum":["text","reference"]},
                  {"key":"duration","type":"integer","min":4,"max":15}
                ]}
                """);

        assertThatThrownBy(() -> service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"generationMode\":\"unknown\",\"duration\":20}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("generationMode");
    }

    @Test
    void rejectsStringLengthAndNumericStepViolations() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(11L);
        when(fieldMapper.findActiveFields(11L)).thenReturn(List.of());
        AgentModelConfig model = readyModel("""
                {"version":"1","fields":[
                  {"key":"prompt","type":"string","minLength":1,"maxLength":5},
                  {"key":"numFrames","type":"integer","min":1,"max":441,"step":8}
                ]}
                """);

        assertThatThrownBy(() -> service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"prompt\":\"123456\",\"numFrames\":9}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("prompt");
        assertThatThrownBy(() -> service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"prompt\":\"ok\",\"numFrames\":10}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("numFrames");
    }

    @Test
    void requiresRelatedMediaBeforeAcceptingReferenceAudio() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(12L);
        when(fieldMapper.findActiveFields(12L)).thenReturn(List.of());
        AgentModelConfig model = readyModel("""
                {"version":"1","fields":[
                  {"key":"referenceImages","type":"array"},
                  {"key":"referenceVideos","type":"array"},
                  {"key":"referenceAudios","type":"array",
                   "requiresAny":["referenceImages","referenceVideos"]}
                ]}
                """);

        assertThatThrownBy(() -> service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"referenceAudios\":[\"voice.mp3\"]}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("referenceAudios");

        ObjectNode accepted = (ObjectNode) service.validateAndSanitize(
                tool,
                model,
                objectMapper.readTree("{\"referenceVideos\":[\"clip.mp4\"],\"referenceAudios\":[\"voice.mp3\"]}"));
        assertThat(accepted.path("referenceAudios").size()).isEqualTo(1);
    }

    @Test
    void enforcesNestedConditionalRequirementsAfterApplyingDefaults() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(13L);
        when(fieldMapper.findActiveFields(13L)).thenReturn(List.of());
        AgentModelConfig model = readyModel("""
                {"version":"1","fields":[
                  {"key":"generationMode","type":"string","required":true,"enum":["generate","add_vocals"]},
                  {"key":"customMode","type":"boolean","default":false},
                  {"key":"instrumental","type":"boolean","default":false},
                  {"key":"prompt","type":"string","requiredWhen":{"anyOf":[
                    {"generationMode":["add_vocals"]},
                    {"generationMode":["generate"],"customMode":[false]},
                    {"generationMode":["generate"],"customMode":[true],"instrumental":[false]}
                  ]}}
                ]}
                """);

        assertThatThrownBy(() -> service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"generationMode\":\"generate\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("prompt");

        ObjectNode accepted = (ObjectNode) service.validateAndSanitize(
                tool,
                model,
                objectMapper.readTree("{\"generationMode\":\"generate\",\"customMode\":true,\"instrumental\":true}"));
        assertThat(accepted.has("prompt")).isFalse();
    }

    @Test
    void requiresReferenceMediaForMatchingRootGroup() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(14L);
        when(fieldMapper.findActiveFields(14L)).thenReturn(List.of());
        AgentModelConfig model = readyModel("""
                {"version":"1","requiresAnyGroups":[
                  {"when":{"generationMode":["multimodal_reference"]},
                   "fields":["referenceImages","referenceVideos"]}
                ],"fields":[
                  {"key":"generationMode","type":"string","required":true,
                   "enum":["text_to_video","multimodal_reference"]},
                  {"key":"referenceImages","type":"array",
                   "visibleWhen":{"generationMode":["multimodal_reference"]}},
                  {"key":"referenceVideos","type":"array",
                   "visibleWhen":{"generationMode":["multimodal_reference"]}}
                ]}
                """);

        assertThatThrownBy(() -> service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"generationMode\":\"multimodal_reference\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("referenceImages/referenceVideos");

        ObjectNode accepted = (ObjectNode) service.validateAndSanitize(
                tool,
                model,
                objectMapper.readTree("""
                        {"generationMode":"multimodal_reference",
                         "referenceVideos":["clip.mp4"]}
                        """));
        assertThat(accepted.path("referenceVideos").size()).isEqualTo(1);

        ObjectNode textOnly = (ObjectNode) service.validateAndSanitize(
                tool, model, objectMapper.readTree("{\"generationMode\":\"text_to_video\"}"));
        assertThat(textOnly.path("generationMode").asText()).isEqualTo("text_to_video");
    }

    private AgentModelConfig readyModel(String schema) {
        AgentModelConfig model = new AgentModelConfig();
        model.setContractStatus("READY");
        model.setRequestSchemaJson(schema);
        return model;
    }
}
