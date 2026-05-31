package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
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
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class InternalTaskServiceImpl implements InternalTaskService {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalTaskServiceImpl.class);

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentToolDescriptorService agentToolDescriptorService;
    private final ModelCapabilityService modelCapabilityService;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;
    private final BillingService billingService;
    private final TaskMetrics taskMetrics;
    private final CommunityService communityService;

    public InternalTaskServiceImpl(TaskMapper taskMapper, ToolMapper toolMapper,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   AgentToolDescriptorService agentToolDescriptorService,
                                   ModelCapabilityService modelCapabilityService,
                                   ToolFieldItemMapper toolFieldItemMapper, ObjectMapper objectMapper,
                                   CreditService creditService, BillingService billingService,
                                   TaskMetrics taskMetrics, CommunityService communityService) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.modelCapabilityService = modelCapabilityService;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.objectMapper = objectMapper;
        this.creditService = creditService;
        this.billingService = billingService;
        this.taskMetrics = taskMetrics;
        this.communityService = communityService;
    }

    @Override
    public ExecutionContextResponse executionContext(Long taskId) {
        AiTask task = findTask(taskId);
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(task.getToolId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        AiTool tool = toolMapper.findById(task.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(tool);
        modelCapabilityService.validateExecution(tool, modelConfig);
        List<String> caps = modelCapabilityService.resolveCapabilities(modelConfig);
        return ExecutionContextResponse.of(task, parseParams(task.getParamsJson()),
                ExecutionModelConfigResponse.from(modelConfig, caps), fields);
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
        AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(billingTool);
        int actualCredits = calculateActualTaskCredits(request, modelConfig, task.getEstimatedCreditCost());
        int chargedCredits = creditService.settleCompleted(task.getUserId(), CreditSourceType.TASK, taskId, actualCredits);
        int estimated = task.getEstimatedCreditCost() == null ? 0 : task.getEstimatedCreditCost();
        if (actualCredits < estimated) {
            creditService.release(task.getUserId(), CreditSourceType.TASK, taskId, estimated - actualCredits);
        }
        if (chargedCredits < actualCredits) {
            LOGGER.warn(
                    "task success saved with incomplete credit settlement taskId={} userId={} expectedCredits={} chargedCredits={}",
                    taskId, task.getUserId(), actualCredits, chargedCredits
            );
        }
        billingService.recordUsage("TASK", taskId, task.getUserId(), modelConfig,
                request.promptTokens(), request.completionTokens(), request.billableUnits(), chargedCredits);
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

    private int calculateActualTaskCredits(WorkerSuccessRequest request, AgentModelConfig modelConfig, Integer fallbackCreditsObj) {
        int fallbackCredits = fallbackCreditsObj == null ? 0 : Math.max(0, fallbackCreditsObj);
        if (modelConfig == null) {
            return fallbackCredits;
        }
        BigDecimal costAmount = BigDecimal.ZERO;
        String billingUnit = modelConfig.getBillingUnit();
        if ("PER_CALL".equals(billingUnit) && modelConfig.getUnitPrice() != null) {
            int units = request.billableUnits() != null && request.billableUnits() > 0 ? request.billableUnits() : 1;
            costAmount = modelConfig.getUnitPrice().multiply(BigDecimal.valueOf(units));
        } else {
            int prompt = request.promptTokens() != null ? Math.max(0, request.promptTokens()) : 0;
            int completion = request.completionTokens() != null ? Math.max(0, request.completionTokens()) : 0;
            if (prompt == 0 && completion == 0) {
                return fallbackCredits;
            }
            BigDecimal inputPrice = modelConfig.getInputTokenPricePer1m() != null
                    ? modelConfig.getInputTokenPricePer1m() : BigDecimal.ZERO;
            BigDecimal outputPrice = modelConfig.getOutputTokenPricePer1m() != null
                    ? modelConfig.getOutputTokenPricePer1m() : BigDecimal.ZERO;
            costAmount = inputPrice.multiply(BigDecimal.valueOf(prompt))
                    .add(outputPrice.multiply(BigDecimal.valueOf(completion)))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        }
        if (costAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackCredits;
        }
        BigDecimal customerCharge = costAmount.multiply(new BigDecimal("1.20"));
        return customerCharge.divide(new BigDecimal("0.01"), 0, RoundingMode.CEILING).intValue();
    }

    @Override
    @Transactional
    public TaskStatusResponse markFailed(Long taskId, WorkerFailedRequest request) {
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
        AiTask task = findTask(taskId);
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
}
