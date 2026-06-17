package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRoutePreviewResolverTest {

    private final ModelRoutePreviewResolver resolver = new ModelRoutePreviewResolver(new ObjectMapper());

    @Test
    void executionTaskRoutesKlingOmniVideo() {
        AgentModelConfig config = new AgentModelConfig();
        config.setProvider("kling_video");
        config.setExecutionTask("omni_video");
        config.setExtraAuthJson("{\"apiTask\":\"text2video\"}");

        ModelRoutePreviewResolver.RoutePreview preview = resolver.resolve(config);

        assertEquals("/v1/videos/omni-video", preview.createPath());
        assertEquals("/v1/videos/omni-video/{task_id}", preview.resultPath());
        assertEquals("executionTask", preview.source());
    }

    @Test
    void legacyExtraAuthTaskStillWorks() {
        AgentModelConfig config = new AgentModelConfig();
        config.setProvider("kling_video");
        config.setExtraAuthJson("{\"apiTask\":\"motion_control\"}");

        ModelRoutePreviewResolver.RoutePreview preview = resolver.resolve(config);

        assertEquals("/v1/videos/motion-control", preview.createPath());
        assertEquals("/v1/videos/motion-control/{task_id}", preview.resultPath());
        assertEquals("legacyExtraAuthJson", preview.source());
    }

    @Test
    void executionOptionsCanOverrideEndpointForAdvancedCompatibility() {
        AgentModelConfig config = new AgentModelConfig();
        config.setProvider("kling_video");
        config.setExecutionOptionsJson("{\"createPath\":\"/custom/create\",\"resultPath\":\"/custom/result/{task_id}\"}");

        ModelRoutePreviewResolver.RoutePreview preview = resolver.resolve(config);

        assertEquals("/custom/create", preview.createPath());
        assertEquals("/custom/result/{task_id}", preview.resultPath());
        assertEquals("executionOptionsJson", preview.source());
    }
}
