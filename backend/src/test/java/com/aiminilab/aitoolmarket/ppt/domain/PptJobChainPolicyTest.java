package com.aiminilab.aitoolmarket.ppt.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PptJobChainPolicyTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void oneClickGenerationFollowsThePersistedFourStagePipeline() {
        var payload = objectMapper.createObjectNode().put("autoContinue", true);

        assertEquals(PptJobType.GENERATE_DESCRIPTIONS,
                PptJobChainPolicy.next(PptJobType.GENERATE_OUTLINE, payload));
        assertEquals(PptJobType.GENERATE_IMAGES,
                PptJobChainPolicy.next(PptJobType.GENERATE_DESCRIPTIONS, payload));
        assertEquals(PptJobType.EXPORT_PPTX,
                PptJobChainPolicy.next(PptJobType.GENERATE_IMAGES, payload));
        assertNull(PptJobChainPolicy.next(PptJobType.EXPORT_PPTX, payload));
    }

    @Test
    void manualJobsNeverStartAnUnexpectedAutomaticChain() {
        var payload = objectMapper.createObjectNode();
        assertNull(PptJobChainPolicy.next(PptJobType.GENERATE_OUTLINE, payload));
        assertNull(PptJobChainPolicy.next(PptJobType.GENERATE_OUTLINE, null));
    }
}
