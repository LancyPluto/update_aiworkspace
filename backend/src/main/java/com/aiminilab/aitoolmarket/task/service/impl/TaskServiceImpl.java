package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.mapper.CommunityEventMapper;
import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.credit.dto.PricingBreakdownItem;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.AgentTaskSourceResponse;
import com.aiminilab.aitoolmarket.task.dto.EstimateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.RegenerateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.StaleTaskReconcileResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskEstimateResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskResultResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.task.service.TaskCreditDispatchService;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.task.support.TaskParamMediaFields;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.service.AgentAttachmentUrlResolver;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowInteractionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmissionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentToolCallMapper agentToolCallMapper;
    private final ModelCapabilityService modelCapabilityService;
    private final ModelExecutionSnapshotService modelExecutionSnapshotService;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    private final TaskOutboxService taskOutboxService;
    private final TaskMetrics taskMetrics;
    private final TaskCreditEstimateService taskCreditEstimateService;
    private final TaskCreditDispatchService taskCreditDispatchService;
    private final CommunityEventMapper communityEventMapper;
    private final CommunityPostMapper communityPostMapper;
    private final AgentAttachmentUrlResolver agentAttachmentUrlResolver;
    private final AssetStorageService assetStorageService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowStepAttemptMapper workflowStepAttemptMapper;
    private final WorkflowInteractionService workflowInteractionService;
    private final WorkflowRuntimeAdmissionService workflowRuntimeAdmissionService;
    private final TaskIdempotencyRecoveryService taskIdempotencyRecoveryService;
    private final TransactionTemplate transactionTemplate;

    public TaskServiceImpl(
            TaskMapper taskMapper,
            ToolMapper toolMapper,
            AgentModelConfigMapper agentModelConfigMapper,
            AgentToolCallMapper agentToolCallMapper,
            ModelCapabilityService modelCapabilityService,
            ModelExecutionSnapshotService modelExecutionSnapshotService,
            CreditService creditService,
            ObjectMapper objectMapper,
            TaskOutboxService taskOutboxService,
            TaskMetrics taskMetrics,
            TaskCreditEstimateService taskCreditEstimateService,
            TaskCreditDispatchService taskCreditDispatchService,
            CommunityEventMapper communityEventMapper,
            CommunityPostMapper communityPostMapper,
            AgentAttachmentUrlResolver agentAttachmentUrlResolver,
            AssetStorageService assetStorageService,
            @Lazy WorkflowExecutionService workflowExecutionService,
            WorkflowRunMapper workflowRunMapper,
            WorkflowStepAttemptMapper workflowStepAttemptMapper,
            @Lazy WorkflowInteractionService workflowInteractionService,
            WorkflowRuntimeAdmissionService workflowRuntimeAdmissionService,
            TaskIdempotencyRecoveryService taskIdempotencyRecoveryService,
            TransactionTemplate transactionTemplate
    ) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolCallMapper = agentToolCallMapper;
        this.modelCapabilityService = modelCapabilityService;
        this.modelExecutionSnapshotService = modelExecutionSnapshotService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.taskOutboxService = taskOutboxService;
        this.taskMetrics = taskMetrics;
        this.taskCreditEstimateService = taskCreditEstimateService;
        this.taskCreditDispatchService = taskCreditDispatchService;
        this.communityEventMapper = communityEventMapper;
        this.communityPostMapper = communityPostMapper;
        this.agentAttachmentUrlResolver = agentAttachmentUrlResolver;
        this.assetStorageService = assetStorageService;
        this.workflowExecutionService = workflowExecutionService;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowStepAttemptMapper = workflowStepAttemptMapper;
        this.workflowInteractionService = workflowInteractionService;
        this.workflowRuntimeAdmissionService = workflowRuntimeAdmissionService;
        this.taskIdempotencyRecoveryService = taskIdempotencyRecoveryService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public TaskStatusResponse create(Long userId, CreateTaskRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        AiTask existing = findIdempotentTask(userId, idempotencyKey, request.toolCode());
        if (existing != null) {
            return TaskStatusResponse.from(existing);
        }
        return createNewTask(userId, request.toolCode(), request.params(), idempotencyKey,
                request.sourcePostId(), request.modelConfigId(), true);
    }

    @Override
    @Transactional
    public TaskStatusResponse createForAgentTool(Long userId, CreateTaskRequest request) {
        return createForAgentTool(userId, request, 0);
    }

    @Override
    @Transactional
    public TaskStatusResponse createForAgentTool(Long userId, CreateTaskRequest request, int excludeFrozen) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        AiTask existing = findIdempotentTask(userId, idempotencyKey, request.toolCode());
        if (existing != null) {
            return TaskStatusResponse.from(existing);
        }
        AiTool tool = toolMapper.findOnlineByCode(request.toolCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        rejectLegacyWorkflowEntry(tool);
        AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(tool, request.modelConfigId());
        taskCreditDispatchService.ensureDispatchAllowed(userId, tool, modelConfig, excludeFrozen);
        return createNewTask(
                userId,
                request.toolCode(),
                request.params(),
                idempotencyKey,
                request.sourcePostId(),
                request.modelConfigId(),
                true
        );
    }

    @Override
    @Transactional
    public TaskStatusResponse createWorkflowRoot(Long userId,
                                                 String toolCode,
                                                 JsonNode params,
                                                 String clientRequestId) {
        String idempotencyKey = normalizeIdempotencyKey(clientRequestId);
        if (idempotencyKey == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "clientRequestId 不能为空");
        }
        AiTask existing = findIdempotentTask(userId, idempotencyKey, toolCode);
        if (existing != null) {
            return TaskStatusResponse.from(existing);
        }

        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        JsonNode normalizedParams = normalizeTaskParams(userId, params);
        AiTask task = new AiTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setToolId(tool.getId());
        task.setParamsJson(normalizedParams.toString());
        task.setIdempotencyKey(idempotencyKey);
        task.setEstimatedCreditCost(0);
        try {
            taskMapper.insertTask(task);
        } catch (DuplicateKeyException duplicate) {
            return taskIdempotencyRecoveryService.recover(
                    userId,
                    idempotencyKey,
                    tool.getId(),
                    duplicate
            );
        }
        return TaskStatusResponse.from(findTask(task.getId(), userId));
    }

    @Override
    public TaskEstimateResponse estimate(Long userId, EstimateTaskRequest request) {
        AiTool tool = toolMapper.findOnlineByCode(request.toolCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        int available = creditService.account(userId).available();

        if (workflowExecutionService.shouldUseWorkflow(tool)) {
            // Interactive workflow tools are billed per executed step; only a minimal balance is required.
            return new TaskEstimateResponse(0, true, available, available >= 1, List.of());
        }

        JsonNode params = request.params() == null ? objectMapper.createObjectNode() : request.params();
        int fallback = tool.getEstimatedCreditCost() == null ? 0 : Math.max(0, tool.getEstimatedCreditCost());
        if (tool.getModelConfigId() == null) {
            List<PricingBreakdownItem> breakdown = List.of(
                    PricingBreakdownItem.of("预设算力", "按工具预设值", fallback));
            return new TaskEstimateResponse(fallback, false, available, available >= fallback, breakdown);
        }

        AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(tool, request.modelConfigId());
        PricingQuote quote = taskCreditEstimateService.quoteUserFacing(tool, modelConfig, params);
        int credits = quote.chargeCredits();
        return new TaskEstimateResponse(credits, false, available, available >= credits, quote.breakdown());
    }

    @Override
    public TaskStatusResponse status(Long userId, Long taskId) {
        AiTask task = findTask(taskId, userId);
        JsonNode workflowPreview = null;
        if (TaskStatus.AWAITING_USER.name().equals(task.getStatus())
                || TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            workflowPreview = workflowExecutionService.buildWorkflowPreview(task.getId());
        }
        return TaskStatusResponse.from(task, workflowPreview);
    }

    @Override
    public TaskDetailResponse detail(Long userId, Long taskId) {
        return toDetail(findTask(taskId, userId), false);
    }

    @Override
    public PageResponse<TaskDetailResponse> list(Long userId, String status, String toolCode, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<AiTask> rawTasks = taskMapper.findByUserId(userId, status, toolCode, normalizedPageSize, offset);
        List<TaskDetailResponse> tasks = toDetailBatch(rawTasks, false);
        long total = taskMapper.countByUserId(userId, status, toolCode);
        return PageResponse.of(tasks, total, pageNo, pageSize);
    }

    @Override
    public TaskStatusResponse cancel(Long userId, Long taskId) {
        findTask(taskId, userId);
        WorkflowRun workflowRun = workflowRunMapper.selectByRootTaskId(taskId);
        if (workflowRun != null) {
            workflowInteractionService.cancel(taskId, userId, "USER_CANCELLED");
            return TaskStatusResponse.from(findTask(taskId, userId));
        }
        rejectWorkflowChildTask(taskId, "取消");
        return transactionTemplate.execute(status -> cancelStandardTask(userId, taskId));
    }

    private TaskStatusResponse cancelStandardTask(Long userId, Long taskId) {
        AiTask task = findTask(taskId, userId);
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.CANCELLED.name());
        int updated = taskMapper.cancel(taskId, List.of(task.getStatus()));
        if (updated == 0) {
            AiTask current = findTask(taskId, userId);
            TaskStateMachine.ensureTransition(current.getStatus(), TaskStatus.CANCELLED.name());
        } else {
            creditService.release(task.getUserId(), CreditSourceType.TASK, taskId, task.getEstimatedCreditCost());
            taskMetrics.recordTaskOutcome(task.getToolCode(), "CANCELLED", task.getCreatedAt(), findTask(taskId, userId).getFinishedAt());
        }
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    @Override
    @Transactional
    public void delete(Long userId, Long taskId) {
        findTask(taskId, userId);
        int updated = taskMapper.softDeleteForUser(taskId, userId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在");
        }
        communityPostMapper.findByTaskId(taskId).ifPresent(post -> {
            if ("PUBLISHED".equals(post.getStatus())) {
                communityPostMapper.updateOwnerStatus(post.getId(), userId, "UNPUBLISHED");
            }
        });
    }

    @Override
    @Transactional
    public TaskStatusResponse regenerate(Long userId, Long taskId, RegenerateTaskRequest request) {
        AiTask originalTask = findTask(taskId, userId);
        rejectWorkflowTask(taskId, "重新生成");
        return taskMapper.findByUserIdAndIdempotencyKey(userId, request.clientRequestId())
                .map(TaskStatusResponse::from)
                .orElseGet(() -> createNewTask(userId, originalTask.getToolCode(), request.params(),
                        request.clientRequestId(), null, originalTask.getModelConfigId(), true));
    }

    @Override
    public PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId, Long taskId,
                                                      Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<AiTask> rawTasks = taskMapper.findForAdmin(status, toolCode, userId, taskId, normalizedPageSize, offset);
        List<TaskDetailResponse> tasks = toDetailBatch(rawTasks, true);
        long total = taskMapper.countForAdmin(status, toolCode, userId, taskId);
        return PageResponse.of(tasks, total, pageNo, pageSize);
    }

    @Override
    public TaskDetailResponse adminDetail(Long taskId) {
        return toDetail(findTask(taskId), true);
    }

    @Override
    @Transactional
    public TaskStatusResponse adminRetry(Long taskId) {
        AiTask task = findTask(taskId);
        rejectWorkflowTask(taskId, "管理员重试");
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.RETRYING.name());
        if (taskMapper.markRetrying(taskId, List.of(TaskStatus.FAILED.name(), TaskStatus.TIMEOUT.name())) == 0) {
            TaskStateMachine.ensureTransition(findTask(taskId).getStatus(), TaskStatus.RETRYING.name());
        }
        AiTask retryingTask = findTask(taskId);
        TaskStateMachine.ensureTransition(retryingTask.getStatus(), TaskStatus.QUEUED.name());
        if (taskMapper.resetToQueued(taskId, List.of(TaskStatus.RETRYING.name())) == 0) {
            TaskStateMachine.ensureTransition(findTask(taskId).getStatus(), TaskStatus.QUEUED.name());
        }
        creditService.freeze(task.getUserId(), CreditSourceType.TASK, taskId, task.getEstimatedCreditCost());
        taskOutboxService.enqueueTaskRetry(taskId);
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    public TaskStatusResponse adminCancel(Long taskId) {
        AiTask task = findTask(taskId);
        WorkflowRun workflowRun = workflowRunMapper.selectByRootTaskId(taskId);
        if (workflowRun != null) {
            workflowInteractionService.cancel(taskId, workflowRun.getUserId(), "ADMIN_CANCELLED");
            return TaskStatusResponse.from(findTask(taskId));
        }
        rejectWorkflowChildTask(taskId, "管理员取消");
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.CANCELLED.name());
        int updated = taskMapper.cancel(taskId, List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name()));
        if (updated == 0) {
            TaskStateMachine.ensureTransition(findTask(taskId).getStatus(), TaskStatus.CANCELLED.name());
        } else {
            creditService.release(task.getUserId(), CreditSourceType.TASK, taskId, task.getEstimatedCreditCost());
            taskMetrics.recordTaskOutcome(task.getToolCode(), "CANCELLED", task.getCreatedAt(), findTask(taskId).getFinishedAt());
        }
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    @Transactional
    public StaleTaskReconcileResponse adminReconcileStaleTasks(Integer staleMinutes, Integer limit) {
        int normalizedMinutes = staleMinutes == null ? 120 : Math.max(15, Math.min(staleMinutes, 24 * 60));
        int normalizedLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(normalizedMinutes);
        List<AiTask> staleTasks = taskMapper.findStaleActiveTasks(cutoff, normalizedLimit);
        List<Long> timedOutTaskIds = new ArrayList<>();
        for (AiTask task : staleTasks) {
            TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.TIMEOUT.name());
            int updated = taskMapper.markFailed(
                    task.getId(),
                    TaskStatus.TIMEOUT.name(),
                    "STALE_TASK_TIMEOUT",
                    "任务长时间未完成，已自动超时并释放冻结算力",
                    "任务超过 " + normalizedMinutes + " 分钟未完成，后台对账标记为超时",
                    List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name())
            );
            if (updated == 0) {
                continue;
            }
            creditService.release(task.getUserId(), CreditSourceType.TASK, task.getId(), task.getEstimatedCreditCost());
            taskMetrics.recordTaskOutcome(task.getToolCode(), TaskStatus.TIMEOUT.name(), task.getCreatedAt(), findTask(task.getId()).getFinishedAt());
            timedOutTaskIds.add(task.getId());
        }
        return new StaleTaskReconcileResponse(normalizedMinutes, staleTasks.size(), timedOutTaskIds.size(), timedOutTaskIds);
    }

    private TaskStatusResponse createNewTask(Long userId, String toolCode, JsonNode params, String clientRequestId,
                                             Long sourcePostId, Long requestedModelConfigId, boolean chargeTaskCredits) {
        String idempotencyKey = normalizeIdempotencyKey(clientRequestId);
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        rejectLegacyWorkflowEntry(tool);
        AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(tool, requestedModelConfigId);
        modelCapabilityService.validateExecution(tool, modelConfig);
        ModelExecutionSnapshot modelSnapshot = modelExecutionSnapshotService.create(modelConfig);
        if (chargeTaskCredits) {
            taskCreditDispatchService.ensureDispatchAllowed(userId, tool, modelConfig);
        }

        JsonNode normalizedParams = normalizeTaskParams(userId, params);
        AiTask task = new AiTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setToolId(tool.getId());
        task.setModelConfigId(modelConfig == null ? null : modelConfig.getId());
        task.setParamsJson(normalizedParams.toString());
        task.setModelSnapshotJson(modelExecutionSnapshotService.serialize(modelSnapshot));
        task.setIdempotencyKey(idempotencyKey);
        int estimatedCredits = chargeTaskCredits ? estimatedTaskCredits(tool, modelConfig, normalizedParams) : 0;
        task.setEstimatedCreditCost(estimatedCredits);

        Long taskId;
        try {
            taskId = taskMapper.insertTask(task);
        } catch (DuplicateKeyException duplicate) {
            return taskIdempotencyRecoveryService.recover(
                    userId,
                    idempotencyKey,
                    tool.getId(),
                    duplicate
            );
        }
        if (chargeTaskCredits) {
            creditService.freeze(userId, CreditSourceType.TASK, taskId, estimatedCredits);
        }
        if (sourcePostId != null) {
            communityEventMapper.insertEvent(sourcePostId, userId, "task_created", "dashboard", tool.getToolCode(), taskId, 0);
        }
        if (workflowExecutionService.shouldUseWorkflow(tool)) {
            workflowExecutionService.startForRootTask(taskId);
        } else {
            taskOutboxService.enqueueTaskCreated(taskId);
        }
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    private AiTask findIdempotentTask(Long userId, String idempotencyKey, String requestedToolCode) {
        AiTask existing = taskMapper.findByUserIdAndIdempotencyKeyIncludingDeleted(userId, idempotencyKey)
                .orElse(null);
        if (existing == null) {
            return null;
        }
        ensureSameIdempotentTool(existing.getToolCode(), requestedToolCode);
        return existing;
    }

    private void rejectLegacyWorkflowEntry(AiTool tool) {
        if (tool != null && "WORKFLOW".equalsIgnoreCase(tool.getExecutionMode())) {
            workflowRuntimeAdmissionService.rejectLegacyWorkflowEntry();
        }
    }

    private void ensureSameIdempotentTool(String existingToolCode, String requestedToolCode) {
        if (existingToolCode == null
                || requestedToolCode == null
                || !existingToolCode.equalsIgnoreCase(requestedToolCode)) {
            throw idempotencyConflict();
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    }

    private BusinessException idempotencyConflict() {
        return new BusinessException(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                "clientRequestId 已用于其他工具"
        );
    }

    private int estimatedTaskCredits(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        if (tool == null) {
            return 0;
        }
        if (workflowExecutionService.shouldUseWorkflow(tool)) {
            return 0;
        }
        if (tool.getModelConfigId() == null) {
            return tool.getEstimatedCreditCost() == null ? 0 : Math.max(0, tool.getEstimatedCreditCost());
        }
        return taskCreditEstimateService.estimateUserFacingTaskCredits(tool, modelConfig, params);
    }

    private TaskDetailResponse toDetail(AiTask task, boolean forAdmin) {
        TaskResultResponse result = taskMapper.findFirstResult(task.getId()).orElse(null);
        result = rewriteResultUrls(result, forAdmin);
        int consumedCredits = taskMapper.sumConsumedCreditsByTaskId(task.getId());
        AgentTaskSourceResponse agentSource = agentToolCallMapper.findByTaskId(task.getId())
                .map(this::toAgentTaskSource)
                .orElse(null);
        Long communityPostId = communityPostMapper.findByTaskId(task.getId())
                .filter(post -> "PUBLISHED".equalsIgnoreCase(post.getStatus()))
                .filter(post -> post.getAuditStatus() == null || "APPROVED".equalsIgnoreCase(post.getAuditStatus()))
                .map(post -> post.getId())
                .orElse(null);
        return TaskDetailResponse.of(task, parseParams(task.getParamsJson()), result, agentSource, communityPostId, consumedCredits);
    }

    private List<TaskDetailResponse> toDetailBatch(List<AiTask> tasks, boolean forAdmin) {
        if (tasks == null || tasks.isEmpty()) return List.of();

        List<Long> taskIds = tasks.stream().map(AiTask::getId).toList();

        List<Map<String, Object>> firstResultRows = taskMapper.batchSelectFirstResults(taskIds);
        Map<Long, String> firstResults = firstResultRows.stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("task_id")).longValue(),
                        row -> {
                            Object ct = row.get("content_text");
                            return ct != null ? (String) ct : "";
                        },
                        (l, r) -> l
                ));
        Map<Long, String> resourceTypes = firstResultRows.stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("task_id")).longValue(),
                        row -> {
                            Object rt = row.get("resource_type");
                            return rt != null ? (String) rt : "";
                        },
                        (l, r) -> l
                ));

        Map<Long, Integer> creditsByTask = taskMapper.batchSumConsumedCredits(taskIds).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("task_id")).longValue(),
                        row -> ((Number) row.get("total_credits")).intValue(),
                        (l, r) -> l
                ));

        Map<Long, AgentToolCall> agentCallsByTask = agentToolCallMapper.batchFindByTaskIds(taskIds).stream()
                .collect(Collectors.toMap(
                        AgentToolCall::getTaskId,
                        Function.identity(),
                        (l, r) -> l
                ));

        Map<Long, CommunityPost> postsByTask = communityPostMapper.batchFindByTaskIds(taskIds).stream()
                .collect(Collectors.toMap(
                        CommunityPost::getTaskId,
                        Function.identity(),
                        (l, r) -> l
                ));

        return tasks.stream().map(task -> {
            String contentText = firstResults.getOrDefault(task.getId(), null);
            String resourceType = resourceTypes.getOrDefault(task.getId(), null);
            TaskResultResponse result = (contentText != null)
                    ? new TaskResultResponse(resourceType, contentText)
                    : null;
            result = rewriteResultUrls(result, forAdmin);

            int consumedCredits = creditsByTask.getOrDefault(task.getId(), 0);

            AgentToolCall agentCall = agentCallsByTask.get(task.getId());
            AgentTaskSourceResponse agentSource = agentCall != null ? toAgentTaskSource(agentCall) : null;

            CommunityPost post = postsByTask.get(task.getId());
            Long communityPostId = null;
            if (post != null
                    && "PUBLISHED".equalsIgnoreCase(post.getStatus())
                    && (post.getAuditStatus() == null || "APPROVED".equalsIgnoreCase(post.getAuditStatus()))) {
                communityPostId = post.getId();
            }

            return TaskDetailResponse.of(task, parseParams(task.getParamsJson()), result, agentSource, communityPostId, consumedCredits);
        }).toList();
    }

    private TaskResultResponse rewriteResultUrls(TaskResultResponse result, boolean forAdmin) {
        if (result == null || result.contentText() == null || result.contentText().isBlank()) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(result.contentText());
            if (root.isObject()) {
                rewriteUrlFields((ObjectNode) root, forAdmin);
                return new TaskResultResponse(result.resourceType(), objectMapper.writeValueAsString(root));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void rewriteUrlFields(ObjectNode node, boolean forAdmin) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        node.fields().forEachRemaining(entries::add);
        for (Map.Entry<String, JsonNode> entry : entries) {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) continue;
            if (value.isTextual() && "url".equals(entry.getKey())) {
                String original = value.asText();
                String rewritten = assetStorageService.rewriteResultUrl(original, forAdmin);
                if (rewritten != null && !rewritten.equals(original)) {
                    node.put(entry.getKey(), rewritten);
                }
                if (!forAdmin) {
                    String downloadUrl = assetStorageService.rewriteDownloadUrl(original);
                    if (downloadUrl != null) {
                        node.put("downloadUrl", downloadUrl);
                    }
                }
            } else if (value.isObject()) {
                rewriteUrlFields((ObjectNode) value, forAdmin);
            } else if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isObject()) {
                        rewriteUrlFields((ObjectNode) item, forAdmin);
                    }
                }
            }
        }
    }

    private AgentTaskSourceResponse toAgentTaskSource(AgentToolCall call) {
        return new AgentTaskSourceResponse(call.getRunId(), call.getId(), call.getToolCode());
    }

    private void rejectWorkflowTask(Long taskId, String operation) {
        if (workflowRunMapper.selectByRootTaskId(taskId) != null) {
            throw workflowTaskOperationRejected(operation);
        }
        rejectWorkflowChildTask(taskId, operation);
    }

    private void rejectWorkflowChildTask(Long taskId, String operation) {
        if (workflowStepAttemptMapper.selectRunIdByChildTaskId(taskId) != null) {
            throw workflowTaskOperationRejected(operation);
        }
    }

    private BusinessException workflowTaskOperationRejected(String operation) {
        return new BusinessException(
                ErrorCode.TASK_STATUS_INVALID,
                "工作流任务不能通过通用任务接口执行" + operation
        );
    }

    private AiTask findTask(Long taskId, Long userId) {
        return taskMapper.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
    }

    private AiTask findTask(Long taskId) {
        return taskMapper.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
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

    private JsonNode normalizeTaskParams(Long userId, JsonNode params) {
        if (params == null || !params.isObject()) {
            return params == null ? objectMapper.createObjectNode() : params;
        }
        ObjectNode normalized = ((ObjectNode) params).deepCopy();
        JsonNode aspectRatio = firstTextual(normalized.get("aspectRatio"), normalized.get("aspect_ratio"), normalized.get("imageRatio"));
        if (aspectRatio != null && !normalized.hasNonNull("aspectRatio")) {
            normalized.set("aspectRatio", aspectRatio);
        }
        rewriteAttachmentUrls(userId, normalized);
        return normalized;
    }

    private void rewriteAttachmentUrls(Long userId, ObjectNode node) {
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isTextual() && TaskParamMediaFields.looksLikeMediaField(key)) {
                String resolved = agentAttachmentUrlResolver.resolveForTaskInput(userId, value.asText());
                node.set(key, TextNode.valueOf(resolved));
                return;
            }
            if (value.isObject()) {
                rewriteAttachmentUrls(userId, (ObjectNode) value);
                return;
            }
            if (value.isArray()) {
                ArrayNode array = (ArrayNode) value;
                for (int i = 0; i < array.size(); i++) {
                    JsonNode item = array.get(i);
                    if (item != null && item.isTextual() && TaskParamMediaFields.looksLikeMediaField(key)) {
                        array.set(i, TextNode.valueOf(agentAttachmentUrlResolver.resolveForTaskInput(userId, item.asText())));
                    } else if (item != null && item.isObject()) {
                        rewriteAttachmentUrls(userId, (ObjectNode) item);
                    }
                }
            }
        });
    }

    private JsonNode firstTextual(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node;
            }
        }
        return null;
    }

    private String generateTaskNo() {
        String date = TASK_NO_DATE.format(LocalDate.now());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "T" + date + suffix;
    }

}
