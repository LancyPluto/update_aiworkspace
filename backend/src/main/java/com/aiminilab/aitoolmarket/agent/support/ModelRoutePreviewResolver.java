package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ModelRoutePreviewResolver {

    private static final Map<String, RoutePair> KLING_ROUTES = Map.of(
            "text2video", new RoutePair("/v1/videos/text2video", "/v1/videos/text2video/{task_id}"),
            "image2video", new RoutePair("/v1/videos/image2video", "/v1/videos/image2video/{task_id}"),
            "motion_control", new RoutePair("/v1/videos/motion-control", "/v1/videos/motion-control/{task_id}"),
            "multi_image2video", new RoutePair("/v1/videos/multi-image2video", "/v1/videos/multi-image2video/{task_id}"),
            "omni_video", new RoutePair("/v1/videos/omni-video", "/v1/videos/omni-video/{task_id}"),
            "image_generation", new RoutePair("/v1/images/generations", "/v1/images/generations/{task_id}"),
            "omni_image", new RoutePair("/v1/images/omni-image", "/v1/images/omni-image/{task_id}")
    );

    private final ObjectMapper objectMapper;

    public ModelRoutePreviewResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RoutePreview resolve(AgentModelConfig config) {
        if (config == null) {
            return null;
        }
        String task = normalizeTask(config.getExecutionTask());
        if (!task.isBlank()) {
            RoutePair pair = pairFor(config.getProvider(), task);
            if (pair != null) {
                return new RoutePreview(pair.createPath(), pair.resultPath(), "executionTask");
            }
        }
        RoutePair optionsPair = pairFromOptions(config.getExecutionOptionsJson());
        if (optionsPair != null) {
            return new RoutePreview(optionsPair.createPath(), optionsPair.resultPath(), "executionOptionsJson");
        }
        String legacyTask = taskFromJson(config.getExtraAuthJson());
        if (!legacyTask.isBlank()) {
            RoutePair pair = pairFor(config.getProvider(), legacyTask);
            if (pair != null) {
                return new RoutePreview(pair.createPath(), pair.resultPath(), "legacyExtraAuthJson");
            }
        }
        RoutePair legacyPair = pairFromOptions(config.getExtraAuthJson());
        if (legacyPair != null) {
            return new RoutePreview(legacyPair.createPath(), legacyPair.resultPath(), "legacyExtraAuthJson");
        }
        RoutePair defaultPair = defaultPair(config);
        if (defaultPair == null) {
            return null;
        }
        return new RoutePreview(defaultPair.createPath(), defaultPair.resultPath(), "default");
    }

    public String taskFromJson(String raw) {
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String key : new String[]{"apiTask", "api_task", "taskType", "task_type"}) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return normalizeTask(value.asText());
            }
        }
        return "";
    }

    public RoutePair pairFromOptions(String raw) {
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return null;
        }
        String createPath = text(node, "createPath", "create_path");
        String resultPath = text(node, "resultPath", "result_path");
        if (!createPath.isBlank() && !resultPath.isBlank()) {
            return new RoutePair(createPath, resultPath);
        }
        return null;
    }

    public static String normalizeTask(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replace('-', '_');
    }

    private RoutePair pairFor(String provider, String task) {
        if ("kling_video".equalsIgnoreCase(provider)) {
            return KLING_ROUTES.get(normalizeTask(task));
        }
        return null;
    }

    private RoutePair defaultPair(AgentModelConfig config) {
        if (!"kling_video".equalsIgnoreCase(config.getProvider())) {
            return null;
        }
        return KLING_ROUTES.get("text2video");
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String primary, String fallback) {
        JsonNode value = node.get(primary);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            value = node.get(fallback);
        }
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    public record RoutePreview(String createPath, String resultPath, String source) {
    }

    public record RoutePair(String createPath, String resultPath) {
    }
}
