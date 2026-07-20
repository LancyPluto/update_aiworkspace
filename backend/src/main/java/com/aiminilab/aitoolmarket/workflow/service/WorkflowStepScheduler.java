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
        int attemptNo = value(step.getAttemptCount()) + 1;
        int maxAttempts = Math.max(1, value(step.getMaxAttempts()));
        if (attemptNo > maxAttempts) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Workflow step attempts exhausted");
        }
        String stableKey = "workflow:%d:step:%d:attempt:%d".formatted(run.getId(), step.getId(), attemptNo);
        int reservedCredits = maxCreditCost(version, node);
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

        AiTask child = createChildTask(run, step, node, stableKey);
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
                                   String stableKey) {
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

        Long modelConfigId = resolveModelConfigId(node);
        if (modelConfigId != null) {
            AgentModelConfig modelConfig = modelConfigMapper.findActiveById(modelConfigId);
            if (modelConfig == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Model configuration does not exist: " + modelConfigId);
            }
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
        if (node.parameters() != null && node.parameters().hasNonNull("maxCreditCost")) {
            int explicit = node.parameters().get("maxCreditCost").asInt();
            if (explicit > 0) {
                return explicit;
            }
        }
        int fallback = readJson(version.getBillingPolicyJson())
                .path("nodePolicies")
                .path(node.id())
                .path("maxCreditCost")
                .asInt(0);
        if (fallback <= 0) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Paid workflow worker step has no reservation cap: " + node.id()
            );
        }
        return fallback;
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
