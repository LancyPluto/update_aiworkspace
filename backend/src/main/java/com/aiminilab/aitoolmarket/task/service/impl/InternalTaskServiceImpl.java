package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.agent.support.OutboundProxyPolicyResolver;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.dto.PricingUsage;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskResponse;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.ExecutionModelConfigResponse;
import com.aiminilab.aitoolmarket.task.dto.ProviderCheckpointRequest;
import com.aiminilab.aitoolmarket.task.dto.ProviderCheckpointResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.task.routing.ModelRoutingService;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.task.support.ProviderCheckpointLimits;
import com.aiminilab.aitoolmarket.task.support.TaskFailureMessage;
import com.aiminilab.aitoolmarket.task.support.TaskParamMediaFields;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.storage.PrivateAssetAccessService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunLockService;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.support.ToolModelCapabilitySupport;
import com.aiminilab.aitoolmarket.tool.support.ToolRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

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
    private final OutboundProxyPolicyResolver outboundProxyPolicyResolver;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;
    private final PricingService pricingService;
    private final BillingService billingService;
    private final TaskMetrics taskMetrics;
    private final CommunityService communityService;
    private final WorkflowStepCallbackService workflowStepCallbackService;
    private final WorkflowRunLockService workflowRunLockService;
    private final PrivateAssetAccessService privateAssetAccessService;
    private final AppProperties appProperties;
    private ModelRoutingService modelRoutingService;

    public InternalTaskServiceImpl(TaskMapper taskMapper, ToolMapper toolMapper,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   AgentToolDescriptorService agentToolDescriptorService,
                                   AgentModelConfigService agentModelConfigService,
                                   ModelCapabilityService modelCapabilityService,
                                   ModelExecutionSnapshotService modelExecutionSnapshotService,
                                   OutboundProxyPolicyResolver outboundProxyPolicyResolver,
                                   ToolFieldItemMapper toolFieldItemMapper, ObjectMapper objectMapper,
                                   CreditService creditService, PricingService pricingService,
                                   BillingService billingService,
                                    TaskMetrics taskMetrics, CommunityService communityService,
                                    WorkflowStepCallbackService workflowStepCallbackService,
                                    WorkflowRunLockService workflowRunLockService,
                                    PrivateAssetAccessService privateAssetAccessService,
                                   AppProperties appProperties) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.agentModelConfigService = agentModelConfigService;
        this.modelCapabilityService = modelCapabilityService;
        this.modelExecutionSnapshotService = modelExecutionSnapshotService;
        this.outboundProxyPolicyResolver = outboundProxyPolicyResolver;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.objectMapper = objectMapper;
        this.creditService = creditService;
        this.pricingService = pricingService;
        this.billingService = billingService;
        this.taskMetrics = taskMetrics;
        this.communityService = communityService;
        this.workflowStepCallbackService = workflowStepCallbackService;
        this.workflowRunLockService = workflowRunLockService;
        this.privateAssetAccessService = privateAssetAccessService;
        this.appProperties = appProperties;
    }

    @Autowired(required = false)
    public void setModelRoutingService(ModelRoutingService modelRoutingService) {
        this.modelRoutingService = modelRoutingService;
    }

    @Override
    public ExecutionContextResponse executionContext(Long taskId) {
        AiTask task = findTask(taskId);
        JsonNode workerParams = resolveParamsForWorker(task.getUserId(), parseParams(task.getParamsJson()));
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(task.getToolId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        AiTool tool = toolMapper.findById(task.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        ModelExecutionSnapshot snapshot = modelExecutionSnapshotService.parse(task.getModelSnapshotJson());
        ToolRuntimeConfig runtimeConfig = ToolRuntimeConfig.fromConfigNote(tool.getConfigNote(), objectMapper);
        ExecutionContextResponse response;
        if (snapshot != null) {
            if (isWorkflowStepTask(task)) {
                validateWorkflowStepSnapshotCapabilities(task, snapshot);
            } else {
                validateSnapshotCapabilities(tool, snapshot);
            }
            var runtimeProxyPolicy = outboundProxyPolicyResolver.resolve(snapshot.toModelConfig());
            response = ExecutionContextResponse.of(task, workerParams,
                    ExecutionModelConfigResponse.from(snapshot, runtimeProxyPolicy), snapshot, fields,
                    runtimeConfig.systemPrompt(), runtimeConfig.adminPrompt());
        } else {
            boolean workflowStep = isWorkflowStepTask(task);
            AgentModelConfig modelConfig = workflowStep
                    ? resolveWorkflowStepModelConfig(task)
                    : resolveTaskModelConfig(task, tool);
            if (workflowStep) {
                validateWorkflowStepModel(task, modelConfig);
            } else {
                modelCapabilityService.validateExecution(tool, modelConfig);
            }
            List<String> caps = modelConfig == null
                    ? List.of()
                    : modelCapabilityService.resolveCapabilities(modelConfig);
            AgentModelConfig executionConfig = modelConfig == null
                    ? null
                    : agentModelConfigService.resolveForExecution(modelConfig);
            response = ExecutionContextResponse.of(task, workerParams,
                    ExecutionModelConfigResponse.from(executionConfig, caps,
                            executionConfig == null ? null : outboundProxyPolicyResolver.resolve(executionConfig)), fields,
                    runtimeConfig.systemPrompt(), runtimeConfig.adminPrompt());
        }
        return response.withProviderCheckpoint(parseProviderCheckpoint(task));
    }

    private void validateSnapshotCapabilities(AiTool tool, ModelExecutionSnapshot snapshot) {
        validateSnapshotCapabilities(ToolModelCapabilitySupport.resolve(tool, objectMapper), snapshot);
    }

    private void validateWorkflowStepSnapshotCapabilities(AiTask task, ModelExecutionSnapshot snapshot) {
        validateSnapshotCapabilities(workflowStepRequiredCapabilities(task), snapshot);
    }

    private void validateSnapshotCapabilities(List<String> required, ModelExecutionSnapshot snapshot) {
        List<String> available = ToolModelCapabilitySupport.normalizeLegacy(snapshot.capabilities());
        List<String> missing = required.stream()
                .filter(capability -> !available.contains(capability))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "model snapshot does not support required capabilities " + missing);
        }
    }

    @Override
    @Transactional
    public ClaimTaskResponse claim(Long taskId, ClaimTaskRequest request) {
        String workerId = cleanClaimPart(request == null ? null : request.workerId(), 128);
        String claimToken = cleanClaimPart(request == null ? null : request.claimToken(), 128);
        AiTask before = findTask(taskId);
        if (workerId == null || claimToken == null) {
            taskMetrics.recordLeaseClaim("denied", "invalid_request");
            return claimResponse(false, before, null, null, "invalid_request");
        }
        if (TaskStateMachine.isTerminal(before.getStatus())) {
            taskMetrics.recordLeaseClaim("denied", "terminal");
            return claimResponse(false, before, claimToken, workerId, "terminal");
        }
        lockWorkflowRunBeforeCoupledMutation(before);
        LocalDateTime now = LocalDateTime.now();
        boolean expiredProcessing = TaskStatus.PROCESSING.name().equals(before.getStatus())
                && before.getLeaseUntil() != null
                && before.getLeaseUntil().isBefore(now);
        LocalDateTime leaseUntil = now.plusMinutes(leaseMinutes());
        int updated = taskMapper.claimForExecution(taskId, workerId, claimToken, leaseUntil);
        if (updated == 0) {
            AiTask current = findTask(taskId);
            String reason = TaskStateMachine.isTerminal(current.getStatus())
                    ? "terminal"
                    : TaskStatus.PROCESSING.name().equals(current.getStatus())
                    ? "already_claimed"
                    : "status_not_claimable";
            taskMetrics.recordLeaseClaim("denied", reason);
            return claimResponse(false, current, claimToken, workerId, reason);
        }
        AiTask claimed = findTask(taskId);
        if (isWorkflowStepTask(claimed)
                && !workflowStepCallbackService.running(taskId, claimed.getLeaseUntil())) {
            throw new BusinessException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "Workflow step attempt is no longer active"
            );
        }
        String reason = expiredProcessing ? "expired_reclaimed" : "claimed";
        taskMetrics.recordLeaseClaim("success", reason);
        return claimResponse(true, claimed, claimToken, workerId, reason);
    }

    @Override
    @Transactional
    public ClaimTaskResponse renewLease(Long taskId, ClaimTaskRequest request) {
        String workerId = cleanClaimPart(request == null ? null : request.workerId(), 128);
        String claimToken = cleanClaimPart(request == null ? null : request.claimToken(), 128);
        AiTask before = findTask(taskId);
        if (claimToken == null) {
            taskMetrics.recordLeaseRenew("denied");
            return claimResponse(false, before, claimToken, workerId, "invalid_request");
        }
        lockWorkflowRunBeforeCoupledMutation(before);
        LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(leaseMinutes());
        int updated = taskMapper.renewLease(taskId, claimToken, leaseUntil);
        if (updated == 0) {
            AiTask current = findTask(taskId);
            String reason = TaskStateMachine.isTerminal(current.getStatus())
                    ? "terminal"
                    : "token_mismatch";
            taskMetrics.recordLeaseRenew("denied");
            return claimResponse(false, current, claimToken, workerId, reason);
        }
        AiTask renewed = findTask(taskId);
        if (isWorkflowStepTask(renewed)
                && !workflowStepCallbackService.leaseRenewed(taskId, renewed.getLeaseUntil())) {
            throw new BusinessException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "Workflow step attempt is no longer active"
            );
        }
        taskMetrics.recordLeaseRenew("success");
        return claimResponse(true, renewed, claimToken, workerId, "renewed");
    }

    @Override
    @Transactional
    public ProviderCheckpointResponse saveProviderCheckpoint(Long taskId, ProviderCheckpointRequest request) {
        AiTask task = findTask(taskId);
        String claimToken = cleanClaimPart(request == null ? null : request.claimToken(), 128);
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())
                || claimToken == null
                || !claimToken.equals(task.getClaimToken())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "任务执行租约不匹配");
        }
        if (request.checkpoint() == null || !request.checkpoint().isObject()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "供应商任务检查点格式无效");
        }
        int expectedVersion = request.expectedVersion() == null ? -1 : request.expectedVersion();
        String checkpointJson = request.checkpoint().toString();
        if (checkpointJson.getBytes(StandardCharsets.UTF_8).length
                > ProviderCheckpointLimits.MAX_PERSISTED_BYTES) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "供应商任务检查点过大");
        }
        lockWorkflowRunBeforeCoupledMutation(task);
        if (taskMapper.updateProviderCheckpointGuarded(
                taskId, claimToken, expectedVersion, checkpointJson) == 0) {
            AiTask current = findTask(taskId);
            int currentVersion = current.getProviderCheckpointVersion() == null
                    ? 0
                    : current.getProviderCheckpointVersion();
            boolean responseReplay = TaskStatus.PROCESSING.name().equals(current.getStatus())
                    && claimToken.equals(current.getClaimToken())
                    && currentVersion == expectedVersion + 1
                    && request.checkpoint().equals(parseProviderCheckpoint(current));
            if (!responseReplay) {
                rejectGuardedCallback(taskId, current, "PROVIDER_CHECKPOINT");
            }
        }
        recordRouteProviderAccepted(taskId, claimToken, request.checkpoint());
        AiTask saved = findTask(taskId);
        return new ProviderCheckpointResponse(
                taskId,
                parseProviderCheckpoint(saved),
                saved.getProviderCheckpointVersion()
        );
    }

    @Override
    @Transactional
    public TaskStatusResponse markProcessing(Long taskId, WorkerProcessingRequest request) {
        int progress = request.progress() == null ? 10 : Math.max(0, Math.min(99, request.progress()));
        String message = request.progressMessage() == null || request.progressMessage().isBlank()
                ? "AI is processing"
                : limitText(request.progressMessage(), 240);
        AiTask task = findTask(taskId);
        String claimToken = cleanClaimPart(request.claimToken(), 128);
        ensureClaimToken(task, claimToken);
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.PROCESSING.name());
        }
        lockWorkflowRunBeforeCoupledMutation(task);
        if (taskMapper.markProcessingGuarded(taskId, claimToken, progress, message,
                List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name())) == 0) {
            TaskStateMachine.ensureTransition(findTask(taskId).getStatus(), TaskStatus.PROCESSING.name());
        }
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    @Transactional
    public TaskStatusResponse markSuccess(Long taskId, WorkerSuccessRequest request) {
        AiTask task = findTask(taskId);
        String claimToken = cleanClaimPart(request.claimToken(), 128);
        ensureClaimToken(task, claimToken);
        lockWorkflowRunBeforeCoupledMutation(task);
        if (isWorkflowStepTask(task)) {
            if (TaskStateMachine.isTerminal(task.getStatus())) {
                workflowStepCallbackService.succeeded(taskId, request);
                completeRoute(taskId, task.getStatus(), null);
                return TaskStatusResponse.from(task);
            }
            if (!TaskStatus.PROCESSING.name().equals(task.getStatus())) {
                throw new BusinessException(
                        ErrorCode.TASK_STATUS_INVALID,
                        "Workflow child task must be claimed before success"
                );
            }
            int updated = taskMapper.markSuccessGuarded(taskId, claimToken,
                    List.of(TaskStatus.PROCESSING.name()));
            if (updated == 0) {
                AiTask current = findTask(taskId);
                if (TaskStateMachine.isTerminal(current.getStatus())) {
                    return TaskStatusResponse.from(current);
                }
                rejectGuardedCallback(taskId, current, TaskStatus.SUCCESS.name());
            }
            if (!workflowStepCallbackService.succeeded(taskId, request)) {
                throw new BusinessException(
                        ErrorCode.TASK_STATUS_INVALID,
                        "Workflow step attempt is no longer active"
                );
            }
            completeRouteSuccess(taskId, request);
            return TaskStatusResponse.from(findTask(taskId));
        }
        if (TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            completeRouteSuccess(taskId, request);
            return TaskStatusResponse.from(task);
        }
        if (TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            completeRoute(taskId, TaskStatus.CANCELLED.name(), null);
            return TaskStatusResponse.from(task);
        }
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            LOGGER.warn(
                    "worker success callback ignored for non-processing task taskId={} currentStatus={} claimedBy={} leaseUntil={}",
                    taskId,
                    task.getStatus(),
                    task.getClaimedBy(),
                    task.getLeaseUntil()
            );
            return TaskStatusResponse.from(task);
        }
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.SUCCESS.name());
        int updated = taskMapper.markSuccessGuarded(taskId, claimToken, List.of(TaskStatus.PROCESSING.name()));
        if (updated == 0) {
            AiTask current = findTask(taskId);
            if (TaskStateMachine.isTerminal(current.getStatus())) {
                return TaskStatusResponse.from(current);
            }
            rejectGuardedCallback(taskId, current, TaskStatus.SUCCESS.name());
        }
        completeRouteSuccess(taskId, request);
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

    private AgentModelConfig resolveWorkflowStepModelConfig(AiTask task) {
        Long modelConfigId = task.getModelConfigId();
        if (modelConfigId == null) {
            JsonNode params = parseParams(task.getParamsJson());
            JsonNode configuredId = params.path("nodeParameters").path("modelConfigId");
            if (!configuredId.isIntegralNumber()) {
                configuredId = params.path("modelConfigId");
            }
            if (configuredId.isIntegralNumber() && configuredId.canConvertToLong()) {
                modelConfigId = configuredId.longValue();
            }
        }
        if (modelConfigId == null) {
            return null;
        }
        AgentModelConfig selected = agentModelConfigMapper.findActiveById(modelConfigId);
        if (selected == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "workflow step model config not found: " + modelConfigId);
        }
        return selected;
    }

    private void validateWorkflowStepModel(AiTask task, AgentModelConfig modelConfig) {
        if (modelConfig == null) {
            return;
        }
        List<String> requiredCapabilities = workflowStepRequiredCapabilities(task);
        if (!requiredCapabilities.isEmpty()) {
            modelCapabilityService.validateModelCapabilities(modelConfig, requiredCapabilities);
        }
        modelCapabilityService.validateModelExecution(modelConfig, requiredCapabilities);
    }

    private List<String> workflowStepRequiredCapabilities(AiTask task) {
        JsonNode params = parseParams(task.getParamsJson());
        String explicit = params.path("nodeParameters").path("requiredCapability").asText("").trim();
        if (!explicit.isEmpty()) {
            return ToolModelCapabilitySupport.normalizeLegacy(List.of(explicit));
        }
        String nodeType = params.path("nodeDefType").asText("").trim().toUpperCase(Locale.ROOT);
        return switch (nodeType) {
            case "LLM_TEXT", "MODEL_CALL" -> List.of("TEXT_GENERATION");
            case "IMAGE_MODEL" -> List.of("IMAGE_GENERATION");
            case "TTS_MODEL" -> List.of("TEXT_TO_SPEECH");
            case "VIDEO_MODEL" -> List.of("VIDEO_GENERATION");
            default -> List.of();
        };
    }

    @Override
    @Transactional
    public TaskStatusResponse markFailed(Long taskId, WorkerFailedRequest request) {
        AiTask task = findTask(taskId);
        String claimToken = cleanClaimPart(request.claimToken(), 128);
        ensureClaimToken(task, claimToken);
        lockWorkflowRunBeforeCoupledMutation(task);
        String errorCode = request.errorCode() == null || request.errorCode().isBlank()
                ? ErrorCode.MODEL_CALL_FAILED.name()
                : request.errorCode();
        String targetStatus = "MODEL_TIMEOUT".equals(errorCode)
                ? TaskStatus.TIMEOUT.name()
                : TaskStatus.FAILED.name();
        String userMessage = ErrorMessageSanitizer.sanitizeUserMessage(
                TaskFailureMessage.userFacingProgressMessage(errorCode, "任务执行失败，请稍后重试"),
                "任务执行失败，请稍后重试"
        );
        String developerSource = request.developerMessage() == null || request.developerMessage().isBlank()
                ? request.errorMessage()
                : request.developerMessage();
        String developerMessage = ErrorMessageSanitizer.sanitizeDeveloperMessage(
                developerSource,
                "Worker execution failed"
        );
        String failureTraceId = cleanClaimPart(request.failureTraceId(), 64);
        if (failureTraceId == null) {
            failureTraceId = cleanClaimPart(MDC.get("traceId"), 64);
        }
        String providerErrorCode = cleanClaimPart(request.providerErrorCode(), 128);
        String providerRequestId = cleanClaimPart(request.providerRequestId(), 128);
        if (isWorkflowStepTask(task)) {
            if (TaskStateMachine.isTerminal(task.getStatus())) {
                workflowStepCallbackService.failed(taskId, request);
                completeRoute(taskId, task.getStatus(), terminalFailure(task.getStatus(), request));
                Long rootTaskId = resolveWorkflowRootTaskId(task);
                return TaskStatusResponse.from(findTask(rootTaskId == null ? taskId : rootTaskId));
            }
            TaskStateMachine.ensureTransition(task.getStatus(), targetStatus);
            int updated = taskMapper.markFailedGuardedWithContract(taskId, claimToken, targetStatus, errorCode,
                    userMessage, developerMessage, failureTraceId, providerErrorCode, providerRequestId,
                    List.of(TaskStatus.PROCESSING.name()));
            if (updated == 0) {
                AiTask current = findTask(taskId);
                if (TaskStateMachine.isTerminal(current.getStatus())) {
                    Long rootTaskId = resolveWorkflowRootTaskId(current);
                    return TaskStatusResponse.from(findTask(rootTaskId == null ? taskId : rootTaskId));
                }
                rejectGuardedCallback(taskId, current, targetStatus);
            }
            if (!workflowStepCallbackService.failed(taskId, request)) {
                throw new BusinessException(
                        ErrorCode.TASK_STATUS_INVALID,
                        "Workflow step attempt is no longer active"
                );
            }
            completeRoute(taskId, targetStatus, request);
            Long rootTaskId = resolveWorkflowRootTaskId(task);
            return TaskStatusResponse.from(findTask(rootTaskId == null ? taskId : rootTaskId));
        }
        if (TaskStatus.FAILED.name().equals(task.getStatus()) || TaskStatus.TIMEOUT.name().equals(task.getStatus())
                || TaskStatus.SUCCESS.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            completeRoute(taskId, task.getStatus(), terminalFailure(task.getStatus(), request));
            return TaskStatusResponse.from(task);
        }
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            LOGGER.warn(
                    "worker failure callback ignored for non-processing task taskId={} currentStatus={} targetStatus={} claimedBy={} leaseUntil={}",
                    taskId,
                    task.getStatus(),
                    targetStatus,
                    task.getClaimedBy(),
                    task.getLeaseUntil()
            );
            return TaskStatusResponse.from(task);
        }
        TaskStateMachine.ensureTransition(task.getStatus(), targetStatus);
        int updated = taskMapper.markFailedGuardedWithContract(taskId, claimToken, targetStatus, errorCode,
                userMessage, developerMessage, failureTraceId, providerErrorCode, providerRequestId,
                List.of(TaskStatus.PROCESSING.name()));
        if (updated == 0) {
            AiTask current = findTask(taskId);
            if (TaskStatus.FAILED.name().equals(current.getStatus()) || TaskStatus.TIMEOUT.name().equals(current.getStatus())
                    || TaskStatus.SUCCESS.name().equals(current.getStatus())
                    || TaskStatus.CANCELLED.name().equals(current.getStatus())) {
                return TaskStatusResponse.from(current);
            }
            rejectGuardedCallback(taskId, current, targetStatus);
        }
        completeRoute(taskId, targetStatus, request);
        creditService.release(task.getUserId(), CreditSourceType.TASK, taskId, task.getEstimatedCreditCost());
        recordFailureCostIfPresent(task, request, targetStatus, errorCode);
        if (shouldMarkToolUnhealthy(errorCode)) {
            agentToolDescriptorService.markToolHealth(task.getToolCode(), "FAILED", developerMessage);
        }
        taskMetrics.recordTaskOutcome(task.getToolCode(), targetStatus, task.getCreatedAt(), findTask(taskId).getFinishedAt());
        return TaskStatusResponse.from(findTask(taskId));
    }

    private void recordFailureCostIfPresent(AiTask task, WorkerFailedRequest request, String outcome, String errorCode) {
        if (Boolean.FALSE.equals(request.providerCharged())) {
            return;
        }
        boolean providerCharged = Boolean.TRUE.equals(request.providerCharged());
        boolean hasExplicitCost = request.providerCostAmount() != null;
        if (!providerCharged && !hasExplicitCost) {
            return;
        }
        String providerCostCurrency;
        if (hasExplicitCost) {
            if (request.providerCostAmount().signum() < 0
                    || request.providerCostCurrency() == null
                    || request.providerCostCurrency().isBlank()) {
                throw new IllegalArgumentException(
                        "Explicit provider cost requires a non-negative amount and currency"
                );
            }
            providerCostCurrency = request.providerCostCurrency();
        } else {
            providerCostCurrency = request.providerCostCurrency() == null
                    || request.providerCostCurrency().isBlank()
                    ? "UNKNOWN"
                    : request.providerCostCurrency();
        }
        AiTool billingTool = toolMapper.findById(task.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        ModelExecutionSnapshot snapshot = modelExecutionSnapshotService.parse(task.getModelSnapshotJson());
        AgentModelConfig modelConfig = snapshot != null
                ? snapshot.toModelConfig()
                : resolveTaskModelConfig(task, billingTool);
        billingService.recordUsage("TASK", task.getId(), task.getUserId(), modelConfig,
                request.promptTokens(), request.completionTokens(), request.billableUnits(), 0,
                request.providerCostAmount(), providerCostCurrency, null,
                outcome, errorCode, normalizeFailureStage(request.failureStage()),
                request.providerErrorCode(), request.providerRequestId(), request.providerCharged());
    }

    private void completeRoute(Long taskId, String outcome, WorkerFailedRequest failure) {
        if (modelRoutingService == null) {
            return;
        }
        if (failure == null) {
            modelRoutingService.completeTask(taskId, outcome);
        } else {
            modelRoutingService.completeTask(taskId, outcome, failure);
        }
    }

    private void completeRouteSuccess(Long taskId, WorkerSuccessRequest request) {
        if (modelRoutingService != null) {
            modelRoutingService.completeSuccess(taskId, request.providerRequestId(), request.providerCalled());
        }
    }

    private void recordRouteProviderAccepted(Long taskId, String claimToken, JsonNode checkpoint) {
        if (modelRoutingService == null) {
            return;
        }
        modelRoutingService.recordProviderAccepted(
                taskId,
                claimToken,
                checkpointProviderRequestId(checkpoint)
        );
    }

    private String checkpointProviderRequestId(JsonNode checkpoint) {
        if (checkpoint == null || !checkpoint.isObject()) {
            return null;
        }
        for (String key : List.of("providerRequestId", "requestId", "providerTaskId", "taskId")) {
            JsonNode value = checkpoint.get(key);
            if (value != null && value.isValueNode()) {
                String normalized = value.asText("").trim();
                if (!normalized.isEmpty()) {
                    return limitText(normalized, 128);
                }
            }
        }
        return null;
    }

    private WorkerFailedRequest terminalFailure(String status, WorkerFailedRequest request) {
        return TaskStatus.FAILED.name().equals(status) || TaskStatus.TIMEOUT.name().equals(status)
                ? request
                : null;
    }

    private String normalizeFailureStage(String failureStage) {
        if (failureStage == null || failureStage.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = failureStage.trim().toUpperCase();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private int leaseMinutes() {
        long configured = appProperties.getTaskExecution() == null ? 30 : appProperties.getTaskExecution().getLeaseMinutes();
        return (int) Math.max(1, Math.min(24 * 60, configured));
    }

    private ClaimTaskResponse claimResponse(boolean claimed, AiTask task, String claimToken, String workerId, String reason) {
        return new ClaimTaskResponse(
                claimed,
                task == null ? null : task.getId(),
                task == null ? null : task.getStatus(),
                claimToken == null && task != null ? task.getClaimToken() : claimToken,
                workerId == null && task != null ? task.getClaimedBy() : workerId,
                task == null ? null : task.getLeaseUntil(),
                task == null ? null : task.getExecutionAttempt(),
                reason
        );
    }

    private void ensureClaimToken(AiTask task, String requestClaimToken) {
        if (task == null || task.getClaimToken() == null || task.getClaimToken().isBlank()) {
            return;
        }
        String normalized = cleanClaimPart(requestClaimToken, 128);
        if (!task.getClaimToken().equals(normalized)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "任务执行租约不匹配");
        }
    }

    private void rejectGuardedCallback(Long taskId, AiTask current, String targetStatus) {
        LOGGER.warn(
                "worker callback rejected after guarded update miss taskId={} currentStatus={} targetStatus={} claimedBy={} leaseUntil={}",
                taskId,
                current == null ? null : current.getStatus(),
                targetStatus,
                current == null ? null : current.getClaimedBy(),
                current == null ? null : current.getLeaseUntil()
        );
        throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "任务状态或执行租约已变化，请忽略本次回调");
    }

    private String cleanClaimPart(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private boolean shouldMarkToolUnhealthy(String errorCode) {
        return "MODEL_AUTH_FAILED".equals(errorCode)
                || "MODEL_CAPABILITY_DISABLED".equals(errorCode)
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

    private JsonNode parseProviderCheckpoint(AiTask task) {
        String checkpointJson = task == null ? null : task.getProviderCheckpointJson();
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return null;
        }
        try {
            JsonNode checkpoint = objectMapper.readTree(checkpointJson);
            if (checkpoint == null || !checkpoint.isObject()) {
                throw new IllegalArgumentException("checkpoint must be an object");
            }
            return checkpoint;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "供应商任务检查点损坏，已拒绝继续执行");
        }
    }

    private JsonNode resolveParamsForWorker(Long userId, JsonNode params) {
        JsonNode resolved = params == null ? objectMapper.createObjectNode() : params.deepCopy();
        rewritePrivateAssetUrls(userId, resolved);
        return resolved;
    }

    private void rewritePrivateAssetUrls(Long userId, JsonNode node) {
        if (!(node instanceof ObjectNode objectNode)) {
            return;
        }
        objectNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isTextual() && TaskParamMediaFields.looksLikeMediaField(key)) {
                String raw = value.asText();
                if (privateAssetAccessService.privateRelativeKey(raw) != null) {
                    objectNode.set(key, TextNode.valueOf(
                            privateAssetAccessService.resolveForWorker(userId, raw)
                    ));
                }
                return;
            }
            if (value.isObject()) {
                rewritePrivateAssetUrls(userId, value);
                return;
            }
            if (value.isArray()) {
                ArrayNode array = (ArrayNode) value;
                for (int i = 0; i < array.size(); i++) {
                    JsonNode item = array.get(i);
                    if (item != null && item.isTextual() && TaskParamMediaFields.looksLikeMediaField(key)) {
                        String raw = item.asText();
                        if (privateAssetAccessService.privateRelativeKey(raw) != null) {
                            array.set(i, TextNode.valueOf(
                                    privateAssetAccessService.resolveForWorker(userId, raw)
                            ));
                        }
                    } else if (item != null && item.isObject()) {
                        rewritePrivateAssetUrls(userId, item);
                    }
                }
            }
        });
    }

    private boolean isWorkflowStepTask(AiTask task) {
        JsonNode params = parseParams(task.getParamsJson());
        return params.path("workflowStep").asBoolean(false);
    }

    private void lockWorkflowRunBeforeCoupledMutation(AiTask task) {
        if (isWorkflowStepTask(task)) {
            workflowRunLockService.requireByChildTaskId(task.getId());
        }
    }

    private Long resolveWorkflowRootTaskId(AiTask task) {
        JsonNode params = parseParams(task.getParamsJson());
        if (params.hasNonNull("parentTaskId")) {
            return params.get("parentTaskId").asLong();
        }
        return null;
    }
}
