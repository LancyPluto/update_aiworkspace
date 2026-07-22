package com.aiminilab.aitoolmarket.tool.support;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolModelCapabilitySupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeDoesNotSilentlyAcceptLegacyDigitalHumanAsANewCapability() {
        assertThat(ToolModelCapabilitySupport.normalize(List.of(
                " image_generation ",
                "DIGITAL_HUMAN",
                "video_generation"
        ))).containsExactly("IMAGE_GENERATION", "DIGITAL_HUMAN", "VIDEO_GENERATION");
    }

    @Test
    void normalizeLegacyMapsDigitalHumanToVideoAndPreservesOtherCapabilities() {
        assertThat(ToolModelCapabilitySupport.normalizeLegacy(List.of(
                " image_generation ",
                "DIGITAL_HUMAN",
                "video_generation"
        ))).containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
    }

    @Test
    void resolveMapsLegacyStoredDigitalHumanToVideo() throws Exception {
        AiTool tool = new AiTool();
        tool.setRequiredModelCapabilities(objectMapper.writeValueAsString(List.of(
                "IMAGE_GENERATION",
                "DIGITAL_HUMAN"
        )));

        assertThat(ToolModelCapabilitySupport.resolve(tool, objectMapper))
                .containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
    }

    @Test
    void defaultsStillMapLegacyDigitalHumanHandlerAndToolTypeToVideo() {
        assertThat(ToolModelCapabilitySupport.defaultsFor("DIGITAL_HUMAN", "AGENT"))
                .containsExactly("VIDEO_GENERATION");
        assertThat(ToolModelCapabilitySupport.defaultsFor(null, "DIGITAL_HUMAN"))
                .containsExactly("VIDEO_GENERATION");
    }
}
