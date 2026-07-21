package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowAttemptStatus;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowReservationResult;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowRunStatus;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowStepStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkflowStepScheduler {

    private final WorkflowRunLockService runLockService;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowStepAttemptMapper attemptMapper;
    private final TaskMapper taskMapper;
    private final ToolWorkflowVersionMapper versionMapper;
    private final WorkflowDslService dslService;
    private final AgentModelConfigMapper modelConfigMapper;
    private final ModelExecutionSnapshotService snapshotService;
    private final TaskOutboxService outboxService;
    private final WorkflowBillingService billingService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public WorkflowStepScheduler(WorkflowRunLockService runLockService,
                                 WorkflowRunStepMapper stepMapper,
                                 WorkflowStepAttemptMapper attemptMapper,
                                 TaskMapper taskMapper,
                                 ToolWorkflowVersionMapper versionMapper,
                                 WorkflowDslService dslService,
                                 AgentModelConfigMapper modelConfigMapper,
                                 ModelExecutionSnapshotService snapshotService,
                                 TaskOutboxService outboxService,
                                 WorkflowBillingService billingService,
                                 ObjectMapper objectMapper,
                                 AppProperties appProperties) {
        this.runLockService = runLockService;
        this.stepMapper = stepMapper;
        this.attemptMapper = attemptMapper;
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.dslService = dslService;
        this.modelConfigMapper = modelConfigMapper;
        this.snapshotService = snapshotService;
        this.outboxService = outboxService;
        this.billingService = billingService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @Transactional
    public WorkflowStepAttempt dispatch(Long stepId) {
        WorkflowRun run = runLockService.requireByStepId(stepId);
        boolean dispatchable = WorkflowRunStatus.RUNNING.name().equals(run.getStatus())
                || WorkflowRunStatus.AWAITING_FUNDS.name().equals(run.getStatus());
        if (!dispatchable) {
            throw new BusinessException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "Workflow run is not running: " + run.getStatus()
            );
        }
        WorkflowRunStep step = requireStepForUpdate(stepId);
        if (!run.getId().equals(step.getRunId())) {
            throw new IllegalStateException("Workflow step changed run while acquiring dispatch locks");
        }
        WorkflowStepAttempt existing = currentActiveAttempt(step);
        if (existing != null) {
            return existing;
        }
        if (!WorkflowStepStatus.READY.name().equals(step.getStatus())) {
            throw new BusinessException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "Workflow step is not ready for dispatch: " + step.getStatus()
            );
        }

        ToolWorkflowVersion version = loadVersion(run);
        WorkflowNodeDef node = parseVersion(version).requireNode(step.getNodeId());
        AgentModelConfig modelConfig = resolveModelConfig(version, node);
        int attemptNo = value(step.getAttemptCount()) + 1;
        int maxAttempts = Math.max(1, value(step.getMaxAttempts()));
        if (attemptNo > maxAttempts) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Workflow step attempts exhausted");
        }
        String stableKey = "workflow:%d:step:%d:attempt:%d".formatted(run.getId(), step.getId(), attemptNo);
        int reservedCredits = reservationCredits(version, node, step);
        WorkflowReservationResult reservation = billingService.reserve(
                run, step, stableKey, reservedCredits
        );
        if (reservation == WorkflowReservationResult.INSUFFICIENT) {
            return null;
        }

        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setStepId(step.getId());
        attempt.setAttemptNo(attemptNo);
        attempt.setCancellationGeneration(run.getCancellationGeneration() == null
                ? 0L
                : run.getCancellationGeneration());
        attempt.setStatus(WorkflowAttemptStatus.CREATED.name());
        attempt.setClaimToken(stableKey);
        attempt.setInputJson(step.getInputJson());
        attemptMapper.insert(attempt);
        if (reservation == WorkflowReservationResult.RESERVED) {
            billingService.bindAttempt(stableKey, attempt.getId());
        }

        AiTask child = createChildTask(run, step, node, stableKey, modelConfig);
        Long childTaskId = taskMapper.insertTask(child);
        LocalDateTime leaseExpiresAt = LocalDateTime.now().plusMinutes(leaseMinutes());
        if (attemptMapper.markDispatched(attempt.getId(), childTaskId, leaseExpiresAt) != 1) {
            throw new IllegalStateException("Workflow attempt could not be dispatched");
        }
        if (stepMapper.activateAttempt(
                step.getId(),
                revision(step),
                step.getCurrentAttemptId(),
                attempt.getId(),
                attemptNo,
                childTaskId
        ) != 1) {
            throw new IllegalStateException("Workflow step dispatch lost its compare-and-set");
        }
        outboxService.enqueueTaskCreated(childTaskId);
        return attemptMapper.selectById(attempt.getId());
    }

    @Transactional
    public WorkflowStepAttempt retry(Long stepId) {
        return dispatch(stepId);
    }

    private WorkflowStepAttempt currentActiveAttempt(WorkflowRunStep step) {
        if (step.getCurrentAttemptId() == null) {
            return null;
        }
        if (!WorkflowStepStatus.QUEUED.name().equals(step.getStatus())
                && !WorkflowStepStatus.RUNNING.name().equals(step.getStatus())) {
            return null;
        }
        WorkflowStepAttempt attempt = attemptMapper.selectById(step.getCurrentAttemptId());
        if (attempt == null) {
            return null;
        }
        if (WorkflowAttemptStatus.DISPATCHED.name().equals(attempt.getStatus())
                || WorkflowAttemptStatus.QUEUED.name().equals(attempt.getStatus())
                || WorkflowAttemptStatus.RUNNING.name().equals(attempt.getStatus())) {
            return attempt;
        }
        return null;
    }

    private AiTask createChildTask(WorkflowRun run,
                                   WorkflowRunStep step,
                                   WorkflowNodeDef node,
                                   String stableKey,
                                   AgentModelConfig modelConfig) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("workflowRunId", run.getId());
        params.put("workflowStepId", step.getId());
        params.put("parentTaskId", run.getRootTaskId());
        params.put("nodeId", step.getNodeId());
        params.put("nodeDefType", node.type().name());
        params.set("nodeParameters", node.parameters());
        String handlerKey = text(node.parameters(), "handlerKey");
        if (handlerKey == null) {
            handlerKey = text(node.parameters(), "operation");
        }
        if (handlerKey != null) {
            params.put("handlerKey", handlerKey);
            params.put("operation", handlerKey);
        }
        params.set("workflowInputs", readJson(step.getInputJson()));
        params.put("workflowStep", true);

        AiTask child = new AiTask();
        child.setTaskNo(generateTaskNo());
        child.setUserId(run.getUserId());
        child.setToolId(run.getToolId());
        child.setParamsJson(writeJson(params));
        child.setEstimatedCreditCost(0);
        child.setIdempotencyKey(stableKey);

        if (modelConfig != null) {
            ModelExecutionSnapshot snapshot = snapshotService.create(modelConfig);
            child.setModelSnapshotJson(snapshotService.serialize(snapshot));
        }
        return child;
    }

    private WorkflowDsl loadDsl(WorkflowRun run) {
        return parseVersion(loadVersion(run));
    }

    private ToolWorkflowVersion loadVersion(WorkflowRun run) {
        ToolWorkflowVersion version = run.getWorkflowVersionId() == null
                ? versionMapper.selectByWorkflowIdAndVersion(run.getWorkflowId(), run.getWorkflowVersion())
                : versionMapper.selectById(run.getWorkflowVersionId());
        if (version == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Workflow version snapshot does not exist");
        }
        return version;
    }

    private WorkflowDsl parseVersion(ToolWorkflowVersion version) {
        return dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson());
    }

    private Long resolveModelConfigId(WorkflowNodeDef node) {
        if (node.parameters() != null && node.parameters().hasNonNull("modelConfigId")) {
            return node.parameters().get("modelConfigId").asLong();
        }
        return null;
    }

    private int maxCreditCost(ToolWorkflowVersion version, WorkflowNodeDef node) {
        JsonNode nodePolicy = readJson(version.getBillingPolicyJson())
                .path("nodePolicies")
                .path(node.id());
        JsonNode reservation = nodePolicy.get("maxCreditCost");
        if (reservation == null
                || !reservation.isIntegralNumber()
                || !reservation.canConvertToInt()
                || reservation.intValue() < 0
                || (reservation.intValue() == 0
                    && !"LOCAL_ZERO_COST".equals(nodePolicy.path("pricingSource").asText()))) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Workflow worker step has no valid automatic reservation estimate: " + node.id()
            );
        }
        return reservation.intValue();
    }

    private int reservationCredits(ToolWorkflowVersion version,
                                   WorkflowNodeDef node,
                                   WorkflowRunStep step) {
        int publishedEstimate = maxCreditCost(version, node);
        String handlerKey = text(node.parameters(), "handlerKey");
        JsonNode operationInput = readJson(step.getInputJson()).path("operationInput");
        if ("comic.script".equals(handlerKey) && hasDirectScript(operationInput)) {
            return 0;
        }
        if ("comic.storyboard".equals(handlerKey) && hasDirectStoryboard(operationInput)) {
            return 0;
        }
        return publishedEstimate;
    }

    private boolean hasDirectScript(JsonNode operationInput) {
        if (!operationInput.isObject()) {
            return false;
        }
        JsonNode nested = findNamedObject(operationInput, "script", "scriptVersion");
        if (nested != null) {
            return hasNonBlankText(nested, "screenplay", "text", "scriptText", "rawText", "content");
        }
        return hasNonBlankText(operationInput, "scriptText", "screenplay", "rawText");
    }

    private boolean hasDirectStoryboard(JsonNode operationInput) {
        if (!operationInput.isObject()) {
            return false;
        }
        JsonNode nested = findNamedValue(operationInput, "storyboard", "storyboardVersion");
        if (nested != null && nested.isObject()
                && selectedShots(nested).isArray()
                && pythonTruthy(selectedShots(nested))) {
            return true;
        }
        JsonNode shots = selectedShots(operationInput);
        return shots.isArray() && pythonTruthy(shots);
    }

    private JsonNode selectedShots(JsonNode value) {
        JsonNode shots = value.get("shots");
        return pythonTruthy(shots) ? shots : value.path("scenes");
    }

    private JsonNode findNamedObject(JsonNode value, String... names) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            for (String name : names) {
                JsonNode direct = value.get(name);
                if (direct != null && direct.isObject()) {
                    return direct;
                }
            }
            var children = value.elements();
            while (children.hasNext()) {
                JsonNode found = findNamedObject(children.next(), names);
                if (found != null) {
                    return found;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) {
                JsonNode found = findNamedObject(child, names);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private AgentModelConfig resolveModelConfig(ToolWorkflowVersion version, WorkflowNodeDef node) {
        Long modelConfigId = resolveModelConfigId(node);
        if (modelConfigId == null) {
            return null;
        }
        AgentModelConfig modelConfig = modelConfigMapper.findActiveById(modelConfigId);
        if (modelConfig == null) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Model configuration does not exist: " + modelConfigId
            );
        }

        JsonNode nodePolicy = readJson(version.getBillingPolicyJson())
                .path("nodePolicies")
                .path(node.id());
        JsonNode pricingSnapshot = nodePolicy.get("modelPricingSnapshot");
        if (!"MODEL_PRICING".equals(nodePolicy.path("pricingSource").asText())
                || pricingSnapshot == null
                || !pricingSnapshot.isObject()) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Workflow model pricing snapshot is unavailable; republish the workflow: " + node.id()
            );
        }
        if (pricingSnapshot.path("id").asLong(-1L) != modelConfigId) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Workflow model pricing snapshot does not match the configured model; republish the workflow: "
                            + node.id()
            );
        }
        String publishedUnit = normalizeBillingUnit(pricingSnapshot.path("billingUnit").asText(null));
        String currentUnit = normalizeBillingUnit(modelConfig.getBillingUnit());
        if (publishedUnit == null || !publishedUnit.equals(currentUnit)) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Model billing unit changed after workflow publication; republish the workflow: " + node.id()
            );
        }
        return modelConfig;
    }

    private String normalizeBillingUnit(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private JsonNode findNamedValue(JsonNode value, String... names) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            for (String name : names) {
                JsonNode direct = value.get(name);
                if (direct != null && !direct.isNull()) {
                    return direct;
                }
            }
            var children = value.elements();
            while (children.hasNext()) {
                JsonNode found = findNamedValue(children.next(), names);
                if (found != null) {
                    return found;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) {
                JsonNode found = findNamedValue(child, names);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean hasNonBlankText(JsonNode value, String... fields) {
        if (value == null || !value.isObject()) {
            return false;
        }
        for (String field : fields) {
            JsonNode text = value.get(field);
            if (text != null && text.isTextual() && !text.asText().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean pythonTruthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.decimalValue().signum() != 0;
        }
        if (value.isTextual()) {
            return !value.textValue().isEmpty();
        }
        return value.size() > 0;
    }

    private WorkflowRunStep requireStepForUpdate(Long stepId) {
        WorkflowRunStep step = stepMapper.selectByIdForUpdate(stepId);
        if (step == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Workflow step does not exist");
        }
        return step;
    }

    private JsonNode readJson(String json) {
        try {
            return json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workflow step input is not valid JSON");
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize workflow child task", exception);
        }
    }

    private int leaseMinutes() {
        long configured = appProperties.getTaskExecution() == null
                ? 30
                : appProperties.getTaskExecution().getLeaseMinutes();
        return (int) Math.max(1, Math.min(24 * 60, configured));
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText().trim();
        return value.isBlank() ? null : value;
    }

    private long revision(WorkflowRunStep step) {
        return step.getRevision() == null ? 0L : step.getRevision();
    }

    private String generateTaskNo() {
        return "WF" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
