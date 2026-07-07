package com.aiminilab.aitoolmarket.agent.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVisionInputSupportTest {

    @Test
    void infersVisionInputForVolcengineDoubaoSeed2ChatModels() {
        List<String> capabilities = AgentVisionInputSupport.withInferredVisionInput(
                "openai_compatible",
                "doubao-seed-2.0-lite",
                "https://ark.cn-beijing.volces.com/api/v3",
                List.of("TEXT_GENERATION")
        );

        assertThat(capabilities).containsExactly("TEXT_GENERATION", "VISION_INPUT");
    }

    @Test
    void doesNotInferVisionInputForDoubaoCodeModel() {
        List<String> capabilities = AgentVisionInputSupport.withInferredVisionInput(
                "openai_compatible",
                "doubao-seed-2.0-code",
                "https://ark.cn-beijing.volces.com/api/v3",
                List.of("TEXT_GENERATION")
        );

        assertThat(capabilities).containsExactly("TEXT_GENERATION");
    }
}
