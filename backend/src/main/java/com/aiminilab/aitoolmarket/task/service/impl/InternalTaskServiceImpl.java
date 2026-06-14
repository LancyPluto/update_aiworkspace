package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.dto.PricingUsage;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.ExecutionModelConfigResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.support.ToolRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InternalTaskServiceImpl implements InternalTaskService {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalTaskServiceImpl.class);

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentToolDescriptorService agentToolDescriptorService;
    private final AgentModelConfigService agentModelConfigService;
    private final ModelCapabilityService modelCapabilityService;
    private final ModelExecutionSnapshotService modelExecutionSnapshotService;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;
    private final PricingService pricingService;
    private final BillingService billingService;
    private final TaskMetrics taskMetrics;
    private final CommunityService communityService;
    private final WorkflowExecutionService workflowExecutionService;

    public InternalTaskServiceImpl(TaskMapper taskMapper, ToolMapper toolMapper,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   AgentToolDescriptorService agentToolDescriptorService,
                                   AgentModelConfigService agentModelConfigService,
                                   ModelCapabilityService modelCapabilityService,
                                   ModelExecutionSnapshotService modelExecutionSnapshotService,
                                   ToolFieldItemMapper toolFieldItemMapper, ObjectMapper objectMapper,
                                   CreditService creditService, PricingService pricingService,
                                   BillingService billingService,
                                   TaskMetrics taskMetrics, CommunityService communityService,
                                   WorkflowExecutionService workflowExecutionService) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.agentModelConfigService = agentModelConfigService;
        this.modelCapabilityService = modelCapabilityService;
        this.modelExecutionSnapshotService = modelExecutionSnapshotService;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.objectMapper = objectMapper;
        this.creditService = creditService;
        this.pricingService = pricingService;
        this.billingService = billingService;
        this.taskMetrics = taskMetrics;
        this.communityService = communityService;
        this.workflowExecutionService = workflowExecutionService;
    }

    @Override
    public ExecutionContextResponse executionContext(Long taskId) {
        AiTask task = findTask(taskId);
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(task.getToolId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        AiTool tool = toolMapper.findById(task.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        ModelExecutionSnapshot snapshot = modelExecutionSnapshotService.parse(task.getModelSnapshotJson());
        ToolRuntimeConfig runtimeConfig = ToolRuntimeConfig.fromConfigNote(tool.getConfigNote(), objectMapper);
        if (snapshot != null) {
            return ExecutionContextResponse.of(task, parseParams(task.getParamsJson()),
                    ExecutionModelConfigResponse.from(snapshot), snapshot, fields,
                    runtimeConfig.systemPrompt(), runtimeConfig.adminPrompt());
        }
        AgentModelConfig modelConfig = resolveTaskModelConfig(task, tool);
        modelCapabilityService.validateExecution(tool, modelConfig);
        List<String> caps = modelCapabilityService.resolveCapabilities(modelConfig);
        AgentModelConfig executionConfig = agentModelConfigService.resolveForExecution(modelConfig);
        return ExecutionContextResponse.of(task, parseParams(task.getParamsJson()),
                ExecutionModelConfigResponse.from(executionConfig, caps), fields,
                runtimeConfig.systemPrompt(), runtimeConfig.adminPrompt());
    }

    @Override
    public TaskStatusResponse markProcessing(Long taskId, WorkerProcessingRequest request) {
        int progress = request.progress() == null ? 10 : Math.max(0, Math.min(99, request.progress()));
        String message = request.progressMessage() == null || request.progressMessage().isBlank()
                ? "AI is processing"
                : limitText(request.progressMessage(), 240);
        AiTask task = findTask(taskId);
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.PROCESSING.name());
        }
        if (taskMapper.markProcessing(taskId, progress, message, List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name())) == 0) {
            TaskStateMachine.ensureTransition(findTask(taskId).getStatus(), TaskStatus.PROCESSING.name());
        }
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    @Transactional
    public TaskStatusResponse markSuccess(Long taskId, WorkerSuccessRequest request) {
        AiTask task = findTask(taskId);
        if (isWorkflowStepTask(task)) {
            workflowExecutionService.onStepTaskSuccess(taskId, request);
            if (!TaskStatus.SUCCESS.name().equals(task.getStatus())) {
                taskMapper.markSuccess(taskId, List.of(TaskStatus.PROCESSING.name(), TaskStatus.QUEUED.name()));
            }
            return TaskStatusResponse.from(findTask(taskId));
        }
        if (TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            return TaskStatusResponse.from(task);
        }
        if (TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return TaskStatusResponse.from(task);
        }
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.SUCCESS.name());
        int updated = taskMapper.markSuccess(taskId, List.of(TaskStatus.PROCESSING.name()));
        if (updated == 0) {
            AiTask current = findTask(taskId);
            if (TaskStatus.SUCCESS.name().equals(current.getStatus()) || TaskStatus.CANCELLED.name().equals(current.getStatus())) {
                return TaskStatusResponse.from(current);
            }
            TaskStateMachine.ensureTransition(current.getStatus(), TaskStatus.SUCCESS.name());
        }
        AiTool billingTool = toolMapper.findById(task.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        ModelExecutionSnapshot snapshot = modelExecutionSnapshotService.parse(task.getModelSnapshotJson());
        AgentModelConfig modelConfig = snapshot != null
                ? snapshot.toModelConfig()
                : resolveTaskModelConfig(task, billingTool);
        int estimated = task.getEstimatedCreditCost() == null ? 0 : Math.max(0, task.getEstimatedCreditCost());
        PricingUsage usage = new PricingUsage(request.promptTokens(), request.completionTokens(), request.billableUnits());
        PricingQuote quote = pricingService.computeQuote(billingTool, modelConfig,
                parseParams(task.getParamsJson()), usage, estimated);
        int actualCredits = quote.chargeCredits();
        int chargedCredits = settleTaskCredits(task.getUserId(), taskId, estimated, actualCredits);
        if (chargedCredits < actualCredits) {
            LOGGER.warn(
                    "task settled below intended charge (credit shortfall) taskId={} userId={} intendedCredits={} chargedCredits={} shortfall={}",
                    taskId, task.getUserId(), actualCredits, chargedCredits, actualCredits - chargedCredits
            );
        }
        billingService.recordUsage("TASK", taskId, task.getUserId(), modelConfig,
                request.promptTokens(), request.completionTokens(), request.billableUnits(),
                actualCredits, quote.vendorCost(), quote.markupRatio());
        taskMapper.insertResult(taskId, task.getUserId(), request.resourceType(), request.contentText());
        try {
            communityService.autoPublishTask(findTask(taskId), request.resourceType(), request.contentText());
        } catch (Exception exception) {
            LOGGER.warn(
                    "community auto-publish skipped after task success taskId={} userId={}",
                    taskId,
                    task.getUserId(),
                    exception
            );
        }
        agentToolDescriptorService.markToolHealth(task.getToolCode(), "HEALTHY", null);
        taskMetrics.recordTaskOutcome(task.getToolCode(), "SUCCESS", task.getCreatedAt(), findTask(taskId).getFinishedAt());
        return TaskStatusResponse.from(findTask(taskId));
    }

    /**
     * Settle a finished task against the credits frozen at creation time, charging the actual amount
     * computed by the pricing engine. Multi-charge / release / over-budget collection are all handled
     * so the account never leaks frozen credits and over-budget runs are collected best-effort.
     *
     * @return credits actually charged (may be below {@code actual} only when the user lacks balance)
     */
    private int settleTaskCredits(Long userId, Long taskId, int estimated, int actual) {
        if (actual <= estimated) {
            int charged = creditService.settleCompleted(userId, CreditSourceType.TASK, taskId, actual);
            if (estimated - actual > 0) {
                creditService.release(userId, CreditSourceType.TASK, taskId, estimated - actual);
            }
            return charged;
        }
        // Actual exceeds the frozen estimate: settle the frozen portion, then collect the remainder.
        int settledFrozen = creditService.settleCompleted(userId, CreditSourceType.TASK, taskId, estimated);
        int extra = actual - settledFrozen;
        int extraCharged = creditService.deductAvailable(userId, CreditSourceType.TASK, taskId, extra);
        return settledFrozen + extraCharged;
    }

    private AgentModelConfig resolveTaskModelConfig(AiTask task, AiTool tool) {
        if (task.getModelConfigId() != null) {
            AgentModelConfig selected = agentModelConfigMapper.findActiveById(task.getModelConfigId());
            if (selected != null) {
                modelCapabilityService.validateExecution(tool, selected);
                return selected;
            }
        }
        return modelCapabilityService.resolveModelConfigForTool(tool);
    }

    @Override
    @Transactional
    public TaskStatusResponse markFailed(Long taskId, WorkerFailedRequest request) {
        AiTask task = findTask(taskId);
        if (isWorkflowStepTask(task)) {
            workflowExecutionService.onStepTaskFailed(taskId, request);
            Long rootTaskId = resolveWorkflowRootTaskId(task);
            return TaskStatusResponse.from(findTask(rootTaskId == null ? taskId : rootTaskId));
        }
        String errorCode = request.errorCode() == null || request.errorCode().isBlank()
                ? ErrorCode.MODEL_CALL_FAILED.name()
                : request.errorCode();
        String targetStatus = "MODEL_TIMEOUT".equals(errorCode)
                ? TaskStatus.TIMEOUT.name()
                : TaskStatus.FAILED.name();
        String errorMessage = request.errorMessage() == null || request.errorMessage().isBlank()
                ? "Worker execution failed"
                : limitText(request.errorMessage(), 4000);
        String progressMessage = limitText(("MODEL_TIMEOUT".equals(errorCode) ? "任务超时：" : "任务失败：") + errorCode, 240);
        if (TaskStatus.FAILED.name().equals(task.getStatus()) || TaskStatus.TIMEOUT.name().equals(task.getStatus())
                || TaskStatus.SUCCESS.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return TaskStatusResponse.from(task);
        }
        TaskStateMachine.ensureTransition(task.getStatus(), targetStatus);
        int updated = taskMapper.markFailed(taskId, targetStatus, errorCode, progressMessage, errorMessage, List.of(TaskStatus.PROCESSING.name()));
        if (updated == 0) {
            AiTask current = findTask(taskId);
            if (TaskStatus.FAILED.name().equals(current.getStatus()) || TaskStatus.TIMEOUT.name().equals(current.getStatus())
                    || TaskStatus.SUCCESS.name().equals(current.getStatus())
                    || TaskStatus.CANCELLED.name().equals(current.getStatus())) {
                return TaskStatusResponse.from(current);
            }
            TaskStateMachine.ensureTransition(current.getStatus(), targetStatus);
        }
        creditService.release(task.getUserId(), CreditSourceType.TASK, taskId, task.getEstimatedCreditCost());
        if (shouldMarkToolUnhealthy(errorCode)) {
            agentToolDescriptorService.markToolHealth(task.getToolCode(), "FAILED", errorMessage);
        }
        taskMetrics.recordTaskOutcome(task.getToolCode(), targetStatus, task.getCreatedAt(), findTask(taskId).getFinishedAt());
        return TaskStatusResponse.from(findTask(taskId));
    }

    private boolean shouldMarkToolUnhealthy(String errorCode) {
        return "MODEL_AUTH_FAILED".equals(errorCode)
                || "MODEL_PROVIDER_UNAVAILABLE".equals(errorCode);
    }

    private AiTask findTask(Long taskId) {
        return taskMapper.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
    }

    private String limitText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 16)) + "...[truncated]";
    }

    private JsonNode parseParams(String paramsJson) {
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            if (node.isTextual()) {
                return objectMapper.readTree(node.asText());
            }
            return node;
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean isWorkflowStepTask(AiTask task) {
        JsonNode params = parseParams(task.getParamsJson());
        return params.path("workflowStep").asBoolean(false);
    }

    private Long resolveWorkflowRootTaskId(AiTask task) {
        JsonNode params = parseParams(task.getParamsJson());
        if (params.hasNonNull("parentTaskId")) {
            return params.get("parentTaskId").asLong();
        }
        return null;
    }
}
