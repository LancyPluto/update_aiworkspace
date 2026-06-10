package com.aiminilab.aitoolmarket.tool.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolKindSupportTest {

    @Test
    void resolvesDigitalHumanBeforePlainVideo() {
        assertThat(ToolKindSupport.resolve(
                "health_digital_human",
                "健康医疗数字人",
                "数字人",
                "VIDEO_GENERATION",
                "TEXT",
                "VIDEO",
                "DIGITAL_HUMAN"
        )).isEqualTo("digitalHuman");
    }

    @Test
    void resolvesCommonMediaKindsAndOtherFallback() {
        assertThat(ToolKindSupport.resolve(null, null, null, "IMAGE_TO_IMAGE", "IMAGE", "IMAGE", "IMAGE_GENERATION"))
                .isEqualTo("image");
        assertThat(ToolKindSupport.resolve(null, null, null, "VIDEO_GENERATION", "IMAGE", "VIDEO", "VIDEO_GENERATION"))
                .isEqualTo("video");
        assertThat(ToolKindSupport.resolve(null, null, null, "TEXT_TO_SPEECH", "TEXT", "AUDIO", "AUDIO_GENERATION"))
                .isEqualTo("audio");
        assertThat(ToolKindSupport.resolve(null, null, null, "AGENT", "MULTIMODAL", "TEXT", "AGENT"))
                .isEqualTo("agent");
        assertThat(ToolKindSupport.resolve(null, null, null, "FILE_PROCESSING", "FILE", "FILE", "CUSTOM"))
                .isEqualTo("other");
    }
}
