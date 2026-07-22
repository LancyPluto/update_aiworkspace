package com.aiminilab.aitoolmarket.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCapabilitiesCodecTest {

    private final ModelCapabilitiesCodec codec = new ModelCapabilitiesCodec(new ObjectMapper());

    @Test
    void parseAndSerializeNormalizeUppercaseTrimAndDuplicates() {
        assertThat(codec.parse("[\" text_generation \",\"TEXT_GENERATION\",\"vision_input\"]"))
                .containsExactly("TEXT_GENERATION", "VISION_INPUT");
        assertThat(codec.serialize(List.of(" text_generation ", "TEXT_GENERATION", "vision_input")))
                .isEqualTo("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
    }
}
