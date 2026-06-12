package com.aiminilab.aitoolmarket.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowContextSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selfReferencingContextFailsJacksonNestingLimit() {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("script-planner", objectMapper.createObjectNode().put("contentText", "scene"));
        ObjectNode output = objectMapper.createObjectNode();
        output.set("artifacts", context);
        context.set("video-output", output);

        assertThrows(Exception.class, () -> objectMapper.writeValueAsString(context));
    }

    @Test
    void artifactSnapshotWithoutSelfReferenceSerializes() {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("script-planner", objectMapper.createObjectNode().put("contentText", "scene"));
        context.set("keyframe", objectMapper.createObjectNode().put("imageUrl", "https://example.com/a.png"));

        ObjectNode artifacts = objectMapper.createObjectNode();
        context.fields().forEachRemaining(entry -> artifacts.set(entry.getKey(), entry.getValue()));
        ObjectNode output = objectMapper.createObjectNode();
        output.set("artifacts", artifacts);
        context.set("video-output", output);

        assertDoesNotThrow(() -> objectMapper.writeValueAsString(context));
    }
}
