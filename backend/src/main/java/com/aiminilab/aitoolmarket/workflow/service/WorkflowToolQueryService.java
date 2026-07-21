package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunDetailResponse;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowToolDetailResponse;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowToolPageResponse;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowToolSummaryResponse;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepCharge;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowConfirmation;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowArtifactQueryMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowArtifactRow;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowToolSurfaceMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowToolSurfaceRow;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class WorkflowToolQueryService {

    private static final List<String> MEDIA_URL_FIELDS = List.of(
            "url", "imageUrl", "videoUrl", "finalVideoUrl", "audioUrl", "fileUrl", "downloadUrl"
    );

    private final ToolMapper toolMapper;
    private final ToolCategoryMapper categoryMapper;
    private final ToolWorkflowMapper workflowMapper;
    private final ToolWorkflowVersionMapper versionMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowStepChargeMapper chargeMapper;
    private final TaskMapper taskMapper;
    private final WorkflowToolSurfaceMapper toolSurfaceMapper;
    private final WorkflowArtifactQueryMapper artifactQueryMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowConfirmationMapper confirmationMapper;
    private final WorkflowConfirmationTokenService confirmationTokenService;

    public WorkflowToolQueryService(ToolMapper toolMapper,
                                    ToolCategoryMapper categoryMapper,
                                    ToolWorkflowMapper workflowMapper,
                                    ToolWorkflowVersionMapper versionMapper,
                                    WorkflowRunMapper runMapper,
                                    WorkflowRunStepMapper stepMapper,
                                    WorkflowStepChargeMapper chargeMapper,
                                    TaskMapper taskMapper,
                                    WorkflowToolSurfaceMapper toolSurfaceMapper,
                                    WorkflowArtifactQueryMapper artifactQueryMapper,
                                    ObjectMapper objectMapper,
                                    WorkflowConfirmationMapper confirmationMapper,
                                    WorkflowConfirmationTokenService confirmationTokenService) {
        this.toolMapper = toolMapper;
        this.categoryMapper = categoryMapper;
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.chargeMapper = chargeMapper;
        this.taskMapper = taskMapper;
        this.toolSurfaceMapper = toolSurfaceMapper;
        this.artifactQueryMapper = artifactQueryMapper;
        this.objectMapper = objectMapper;
        this.confirmationMapper = confirmationMapper;
        this.confirmationTokenService = confirmationTokenService;
    }

    @Transactional(readOnly = true)
    public WorkflowToolPageResponse list(int page, int pageSize, String keyword, String category) {
        String normalizedKeyword = normalize(keyword);
        String normalizedCategory = normalize(category);
        long total = toolSurfaceMapper.count(normalizedKeyword, normalizedCategory);
        long offset = (long) (page - 1) * pageSize;
        List<WorkflowToolSummaryResponse> items = toolSurfaceMapper.selectPage(
                        normalizedKeyword,
                        normalizedCategory,
                        pageSize,
                        offset
                ).stream()
                .map(this::toSummary)
                .toList();
        return new WorkflowToolPageResponse(
                items,
                total,
                page,
                pageSize,
                offset + items.size() < total
        );
    }

    @Transactional(readOnly = true)
    public WorkflowToolDetailResponse detail(String toolCode) {
        PublishedTool published = requirePublishedTool(toolCode);
        AiTool tool = published.tool();
        ToolCategory category = tool.getCategoryId() == null ? null : categoryMapper.selectById(tool.getCategoryId());
        JsonNode config = parseJson(published.version().getConfigJson());
        JsonNode inputSchema = parseJson(published.version().getInputSchemaSnapshotJson());
        return new WorkflowToolDetailResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getDescription(),
                category == null ? null : category.getCategoryName(),
                tool.getCoverUrl(),
                tool.getStatus(),
                tool.getEstimatedCreditCost(),
                tool.getMinimumRequiredCredits(),
                isVariablePricing(tool),
                text(config, "adapterKey"),
                List.of(),
                inputSchema,
                published.version().getVersion(),
                integer(config, "estimatedDurationSeconds"),
                object(config, "adapterData")
        );
    }

    @Transactional(readOnly = true)
    public WorkflowRunDetailResponse runDetail(Long rootTaskId, Long userId) {
        WorkflowRun run = runMapper.selectByRootTaskId(rootTaskId);
        if (run == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "工作流任务不存在");
        }
        if (!Objects.equals(run.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工作流任务");
        }

        AiTask task = taskMapper.findById(rootTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "工作流根任务不存在"));
        AiTool tool = toolMapper.selectById(run.getToolId());
        ToolWorkflowVersion version = run.getWorkflowVersionId() == null
                ? null
                : versionMapper.selectById(run.getWorkflowVersionId());
        JsonNode config = version == null ? JsonNodeFactory.instance.objectNode() : parseJson(version.getConfigJson());

        List<WorkflowRunStep> steps = stepMapper.selectByRunId(run.getId());
        List<WorkflowStepCharge> charges = chargeMapper.selectList(
                new LambdaQueryWrapper<WorkflowStepCharge>()
                        .eq(WorkflowStepCharge::getRunId, run.getId())
                        .orderByAsc(WorkflowStepCharge::getId));
        List<Long> artifactTaskIds = Stream.concat(
                        Stream.of(rootTaskId),
                        steps.stream().map(WorkflowRunStep::getTaskId)
                )
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, List<WorkflowArtifactRow>> artifactsByTask = artifactQueryMapper
                .selectByTaskIds(artifactTaskIds)
                .stream()
                .collect(Collectors.groupingBy(
                        WorkflowArtifactRow::getTaskId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<Long, List<WorkflowStepCharge>> chargesByStep = charges.stream()
                .collect(Collectors.groupingBy(WorkflowStepCharge::getStepId, LinkedHashMap::new, Collectors.toList()));
        List<WorkflowRunDetailResponse.WorkflowStepChargeResponse> chargeResponses = charges.stream()
                .map(this::toCharge)
                .toList();
        List<WorkflowRunDetailResponse.WorkflowRunStepResponse> stepResponses = steps.stream()
                .sorted(Comparator.comparing(WorkflowRunStep::getSequenceNo).thenComparing(WorkflowRunStep::getId))
                .map(step -> toStep(
                        step,
                        chargesByStep.getOrDefault(step.getId(), List.of()),
                        artifactsByTask.getOrDefault(step.getTaskId(), List.of())
                ))
                .toList();
        WorkflowRunDetailResponse.WorkflowCostResponse cost = toCost(charges, chargeResponses);
        WorkflowRunDetailResponse.WorkflowUserActionResponse userAction = toUserAction(run, steps);
        List<WorkflowRunDetailResponse.WorkflowArtifactResponse> artifacts = artifactsByTask
                .getOrDefault(rootTaskId, List.of())
                .stream()
                .map(resource -> toArtifact(resource, null))
                .toList();

        return new WorkflowRunDetailResponse(
                run.getRootTaskId(),
                run.getId(),
                task.getTaskNo(),
                tool == null ? task.getToolCode() : tool.getToolCode(),
                tool == null ? task.getToolName() : tool.getToolName(),
                run.getStatus(),
                task.getProgress(),
                task.getProgressMessage(),
                run.getCurrentStepId(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getUpdatedAt(),
                run.getFinishedAt(),
                task.getErrorCode(),
                run.getErrorMessage() == null ? task.getErrorMessage() : run.getErrorMessage(),
                stepResponses,
                artifacts,
                cost,
                cost.totalCredits(),
                cost.reservedCredits(),
                userAction,
                userAction,
                text(config, "adapterKey"),
                object(config, "adapterData"),
                run.getRevision()
        );
    }

    private WorkflowRunDetailResponse.WorkflowUserActionResponse toUserAction(
            WorkflowRun run,
            List<WorkflowRunStep> steps) {
        if (!"AWAITING_USER".equals(run.getStatus()) || run.getCurrentStepId() == null) {
            return null;
        }
        WorkflowConfirmation confirmation = confirmationMapper.selectPendingByRunId(run.getId());
        if (confirmation == null || !run.getCurrentStepId().equals(confirmation.getStepId())) {
            return null;
        }
        WorkflowRunStep step = steps.stream()
                .filter(candidate -> confirmation.getStepId().equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        if (step == null || !"AWAITING_USER".equals(step.getStatus())) {
            return null;
        }
        return new WorkflowRunDetailResponse.WorkflowUserActionResponse(
                step.getId(),
                "请确认是否继续执行",
                List.of(),
                confirmationTokenService.readAllowedActions(confirmation),
                confirmationTokenService.rawToken(confirmation, run, step),
                confirmation.getExpiresAt()
        );
    }

    private PublishedTool requirePublishedTool(String toolCode) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .filter(candidate -> "WORKFLOW".equalsIgnoreCase(candidate.getExecutionMode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工作流工具不存在或不可用"));
        WorkflowToolSurfaceRow surface = toolSurfaceMapper.selectCanonicalByToolCode(toolCode);
        ToolWorkflow workflow = surface == null ? null : workflowMapper.selectById(surface.getWorkflowId());
        if (workflow == null
                || workflow.getPublishedVersionId() == null) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工作流工具不存在或不可用");
        }
        ToolWorkflowVersion version = versionMapper.selectById(workflow.getPublishedVersionId());
        if (version == null || !Objects.equals(version.getWorkflowId(), workflow.getId())) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工作流工具不存在或不可用");
        }
        return new PublishedTool(tool, workflow, version);
    }

    private WorkflowToolSummaryResponse toSummary(WorkflowToolSurfaceRow tool) {
        JsonNode config = parseJson(tool.getVersionConfigJson());
        return new WorkflowToolSummaryResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getDescription(),
                tool.getCategoryName(),
                tool.getCoverUrl(),
                tool.getStatus(),
                tool.getEstimatedCreditCost(),
                tool.getMinimumRequiredCredits(),
                "WORKFLOW_STEP".equalsIgnoreCase(tool.getBillingMode()),
                text(config, "adapterKey")
        );
    }

    private WorkflowRunDetailResponse.WorkflowRunStepResponse toStep(
            WorkflowRunStep step,
            List<WorkflowStepCharge> charges,
            List<WorkflowArtifactRow> resources) {
        int progress = "SUCCESS".equals(step.getStatus()) ? 100 : 0;
        List<WorkflowRunDetailResponse.WorkflowArtifactResponse> artifacts = resources.stream()
                .map(resource -> toArtifact(resource, step.getId()))
                .toList();
        if (artifacts.isEmpty() && step.getOutputJson() != null && !step.getOutputJson().isBlank()) {
            artifacts = List.of(toOutputArtifact(step));
        }
        return new WorkflowRunDetailResponse.WorkflowRunStepResponse(
                step.getId(),
                step.getId(),
                step.getNodeId(),
                step.getNodeId(),
                step.getNodeDefType(),
                step.getStatus(),
                progress,
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt(),
                null,
                step.getErrorMessage(),
                artifacts,
                charges.stream().map(this::toCharge).toList()
        );
    }

    private WorkflowRunDetailResponse.WorkflowStepChargeResponse toCharge(WorkflowStepCharge charge) {
        return new WorkflowRunDetailResponse.WorkflowStepChargeResponse(
                charge.getId(),
                charge.getStepId(),
                charge.getStatus(),
                charge.getReservedCredits(),
                charge.getChargedCredits(),
                charge.getChargedCredits()
        );
    }

    private WorkflowRunDetailResponse.WorkflowCostResponse toCost(
            List<WorkflowStepCharge> charges,
            List<WorkflowRunDetailResponse.WorkflowStepChargeResponse> responses) {
        int total = charges.stream()
                .filter(charge -> "CAPTURED".equalsIgnoreCase(charge.getStatus()))
                .mapToInt(charge -> value(charge.getChargedCredits()))
                .sum();
        int reserved = charges.stream()
                .filter(charge -> "RESERVED".equalsIgnoreCase(charge.getStatus())
                        || "AWAITING_FUNDS".equalsIgnoreCase(charge.getStatus()))
                .mapToInt(charge -> value(charge.getReservedCredits()))
                .sum();
        int released = charges.stream()
                .filter(charge -> "RELEASED".equalsIgnoreCase(charge.getStatus()))
                .mapToInt(charge -> Math.max(0, value(charge.getReservedCredits()) - value(charge.getChargedCredits())))
                .sum();
        return new WorkflowRunDetailResponse.WorkflowCostResponse(total, reserved, total, released, responses);
    }

    private WorkflowRunDetailResponse.WorkflowArtifactResponse toArtifact(WorkflowArtifactRow resource,
                                                                           Long stepId) {
        String type = normalizeArtifactType(resource.getResourceType());
        String rawContent = resource.getContentText();
        JsonNode structured = shouldParseArtifact(type) ? tryParseJson(rawContent) : null;
        Object content = structured == null ? rawContent : structured;
        String url = isMediaArtifact(type)
                ? (isExplicitUrl(rawContent) ? rawContent.trim() : findMediaUrl(structured))
                : null;
        String downloadUrl = isMediaArtifact(type) ? findNamedUrl(structured, "downloadUrl") : null;
        if (downloadUrl == null) {
            downloadUrl = url;
        }
        return new WorkflowRunDetailResponse.WorkflowArtifactResponse(
                resource.getId(),
                resource.getId(),
                stepId,
                type,
                "运行结果",
                "运行结果",
                url,
                downloadUrl,
                content,
                content,
                null,
                null,
                null,
                null
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isVariablePricing(AiTool tool) {
        return "WORKFLOW_STEP".equalsIgnoreCase(tool.getBillingMode());
    }

    private WorkflowRunDetailResponse.WorkflowArtifactResponse toOutputArtifact(WorkflowRunStep step) {
        JsonNode parsed = tryParseJson(step.getOutputJson());
        boolean textual = parsed == null || parsed.isTextual();
        Object content = parsed == null
                ? step.getOutputJson()
                : (parsed.isTextual() ? parsed.asText() : parsed);
        return new WorkflowRunDetailResponse.WorkflowArtifactResponse(
                null,
                null,
                step.getId(),
                textual ? "TEXT" : "JSON",
                "Step output",
                "Step output",
                null,
                null,
                content,
                content,
                null,
                null,
                null,
                null
        );
    }

    private String normalizeArtifactType(String type) {
        return type == null || type.isBlank() ? "TEXT" : type.trim().toUpperCase(Locale.ROOT);
    }

    private boolean shouldParseArtifact(String type) {
        return "JSON".equals(type) || isMediaArtifact(type);
    }

    private boolean isMediaArtifact(String type) {
        return "IMAGE".equals(type)
                || "VIDEO".equals(type)
                || "AUDIO".equals(type)
                || "FILE".equals(type);
    }

    private JsonNode tryParseJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String findMediaUrl(JsonNode node) {
        if (node == null) {
            return null;
        }
        for (String field : MEDIA_URL_FIELDS) {
            String candidate = findNamedUrl(node, field);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String findNamedUrl(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null && direct.isTextual() && isExplicitUrl(direct.asText())) {
                return direct.asText().trim();
            }
            for (JsonNode child : node) {
                String nested = findNamedUrl(child, field);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findNamedUrl(child, field);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean isExplicitUrl(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.isEmpty()
                && !normalized.matches(".*\\s+.*")
                && (normalized.matches("(?i)^https?://.+$") || normalized.startsWith("/"));
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "已发布工作流配置无法读取");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    private JsonNode object(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isObject() ? value : null;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private record PublishedTool(AiTool tool, ToolWorkflow workflow, ToolWorkflowVersion version) {
    }
}
