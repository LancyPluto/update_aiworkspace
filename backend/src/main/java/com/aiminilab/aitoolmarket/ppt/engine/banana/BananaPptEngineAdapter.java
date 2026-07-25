package com.aiminilab.aitoolmarket.ppt.engine.banana;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.domain.PptJobType;
import com.aiminilab.aitoolmarket.ppt.engine.EngineCapabilities;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobSnapshot;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobState;
import com.aiminilab.aitoolmarket.ppt.engine.EngineProject;
import com.aiminilab.aitoolmarket.ppt.engine.EngineProjectRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineSubmission;
import com.aiminilab.aitoolmarket.ppt.engine.PptEngineAdapter;
import com.aiminilab.aitoolmarket.ppt.service.PptEngineClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class BananaPptEngineAdapter implements PptEngineAdapter {
    public static final String ENGINE_CODE = "BANANA_VISUAL";

    private final PptEngineClient client;
    private final ObjectMapper objectMapper;

    public BananaPptEngineAdapter(PptEngineClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public String engineCode() {
        return ENGINE_CODE;
    }

    @Override
    public EngineCapabilities capabilities() {
        return new EngineCapabilities(
                ENGINE_CODE,
                "Banana Slides 视觉引擎",
                client.healthCheck(),
                true,
                true,
                false,
                Set.of(
                        PptJobType.GENERATE_OUTLINE.name(),
                        PptJobType.GENERATE_DESCRIPTIONS.name(),
                        PptJobType.GENERATE_IMAGES.name(),
                        PptJobType.EXPORT_PPTX.name(),
                        PptJobType.EXPORT_EDITABLE_PPTX.name()
                )
        );
    }

    @Override
    public EngineProject createProject(EngineProjectRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("creation_type", normalizeCreationType(request.creationType()));
        putIfPresent(payload, "idea_prompt", request.topic());
        putIfPresent(payload, "title", request.title());
        putIfPresent(payload, "language", request.language());
        payload.put("template_style",
                "现代、专业、统一的演示文稿视觉；信息层级清晰，留白克制，图文服务于观点表达");
        payload.put("image_aspect_ratio", defaultValue(request.aspectRatio(), "16:9"));
        if (request.pageCount() != null) {
            payload.put("page_count", request.pageCount());
        }
        if (request.platformProjectId() != null) {
            payload.put("platform_project_id", request.platformProjectId());
        }
        putIfPresent(payload, "idempotency_key", request.idempotencyKey());
        JsonNode result = client.createProject(payload);
        String projectId = firstText(result, "project_id", "id");
        if (projectId == null) {
            throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "引擎未返回项目标识");
        }
        return new EngineProject(projectId, defaultValue(text(result, "status"), "DRAFT"));
    }

    @Override
    public EngineSubmission submit(EngineJobRequest request) {
        Map<String, Object> actionPayload = new LinkedHashMap<>(payload(request.payload()));
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("provider", "KCD_PLATFORM");
        platform.put("project_id", request.platformProjectId());
        platform.put("job_id", request.platformJobId());
        platform.put("idempotency_key", request.idempotencyKey());
        platform.put("gateway_base_url", request.modelGatewayBaseUrl());
        platform.put("execution_token", request.executionToken());
        actionPayload.put("platform_execution", platform);
        JsonNode result = switch (request.jobType()) {
            case GENERATE_OUTLINE -> client.postProjectAction(
                    request.externalProjectId(), "/generate/outline", actionPayload);
            case GENERATE_DESCRIPTIONS -> client.postProjectAction(
                    request.externalProjectId(), "/generate/descriptions", actionPayload);
            case GENERATE_IMAGES -> client.postProjectAction(
                    request.externalProjectId(), "/generate/images", actionPayload);
            case EXPORT_PPTX -> client.postProjectAction(
                    request.externalProjectId(), "/export/pptx", actionPayload);
            case EXPORT_EDITABLE_PPTX -> client.postProjectAction(
                    request.externalProjectId(), "/export/editable-pptx", actionPayload);
        };
        String taskId = firstText(result, "task_id", "id");
        if (taskId == null) {
            return new EngineSubmission(EngineJobState.SUCCEEDED, null, 100, "已完成", result);
        }
        EngineJobState state = normalizeState(text(result, "status"));
        if (state == EngineJobState.UNKNOWN || state == EngineJobState.SUCCEEDED) {
            state = EngineJobState.SUBMITTED;
        }
        return new EngineSubmission(
                state,
                taskId,
                progress(result),
                firstText(result, "message", "progress_message"),
                result
        );
    }

    @Override
    public EngineJobSnapshot query(String externalProjectId, String externalJobId) {
        JsonNode result = client.getProjectAction(externalProjectId, "/tasks/" + externalJobId);
        EngineJobState state = normalizeState(text(result, "status"));
        String errorMessage = firstText(result, "error_message", "error", "message");
        return new EngineJobSnapshot(
                state,
                progress(result),
                firstText(result, "message", "progress_message"),
                state == EngineJobState.FAILED ? "PPT_ENGINE_JOB_FAILED" : null,
                state == EngineJobState.FAILED ? errorMessage : null,
                state == EngineJobState.FAILED,
                result
        );
    }

    @Override
    public JsonNode projectSnapshot(String externalProjectId) {
        return client.getProject(externalProjectId);
    }

    @Override
    public boolean cancel(String externalProjectId, String externalJobId) {
        return false;
    }

    @Override
    public byte[] download(String enginePath) {
        return client.downloadFile(enginePath);
    }

    private Map<String, Object> payload(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private EngineJobState normalizeState(String raw) {
        if (raw == null || raw.isBlank()) {
            return EngineJobState.UNKNOWN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING", "QUEUED", "SUBMITTED" -> EngineJobState.SUBMITTED;
            case "PROCESSING", "RUNNING", "IN_PROGRESS" -> EngineJobState.RUNNING;
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "DONE" -> EngineJobState.SUCCEEDED;
            case "FAILED", "ERROR" -> EngineJobState.FAILED;
            case "CANCELLED", "CANCELED" -> EngineJobState.CANCELLED;
            default -> EngineJobState.UNKNOWN;
        };
    }

    private int progress(JsonNode node) {
        JsonNode progress = node == null ? null : node.get("progress");
        if (progress == null || progress.isNull()) {
            return 0;
        }
        if (progress.isNumber()) {
            return Math.max(0, Math.min(100, progress.asInt()));
        }
        if (progress.isObject()) {
            return Math.max(0, Math.min(100, progress.path("percentage").asInt(0)));
        }
        return 0;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isValueNode() ? value.asText() : null;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public EngineSubmission reconcileSubmission(String externalProjectId, String idempotencyKey) {
        JsonNode result = client.getSubmission(externalProjectId, idempotencyKey);
        if (result == null || result.isNull()) {
            return new EngineSubmission(EngineJobState.UNKNOWN, null, 0, "未找到引擎提交记录", null);
        }
        String taskId = firstText(result, "task_id", "id");
        EngineJobState state = normalizeState(text(result, "status"));
        return new EngineSubmission(
                state,
                taskId,
                progress(result),
                firstText(result, "message", "progress_message"),
                result
        );
    }

    private String normalizeCreationType(String value) {
        if (value == null || value.isBlank()) {
            return "idea";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "outline" -> "outline";
            case "description", "descriptions" -> "descriptions";
            case "blank" -> "blank";
            // AI_GENERATED is the platform product term; Banana calls the same
            // project-entry mode "idea".
            case "ai_generated", "idea" -> "idea";
            default -> "idea";
        };
    }
}
