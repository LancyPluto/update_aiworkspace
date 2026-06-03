package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.mapper.CommunityEventMapper;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.AgentTaskSourceResponse;
import com.aiminilab.aitoolmarket.task.dto.RegenerateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskResultResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.task.service.TaskCreditDispatchService;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class TaskServiceImpl implements TaskService {

    private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentToolCallMapper agentToolCallMapper;
    private final ModelCapabilityService modelCapabilityService;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    private final TaskOutboxService taskOutboxService;
    private final TaskMetrics taskMetrics;
    private final TaskCreditEstimateService taskCreditEstimateService;
    private final TaskCreditDispatchService taskCreditDispatchService;
    private final CommunityEventMapper communityEventMapper;
    private final CommunityPostMapper communityPostMapper;

    public TaskServiceImpl(
            TaskMapper taskMapper,
            ToolMapper toolMapper,
            AgentModelConfigMapper agentModelConfigMapper,
            AgentToolCallMapper agentToolCallMapper,
            ModelCapabilityService modelCapabilityService,
            CreditService creditService,
            ObjectMapper objectMapper,
            TaskOutboxService taskOutboxService,
            TaskMetrics taskMetrics,
            TaskCreditEstimateService taskCreditEstimateService,
            TaskCreditDispatchService taskCreditDispatchService,
            CommunityEventMapper communityEventMapper,
            CommunityPostMapper communityPostMapper
    ) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolCallMapper = agentToolCallMapper;
        this.modelCapabilityService = modelCapabilityService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.taskOutboxService = taskOutboxService;
        this.taskMetrics = taskMetrics;
        this.taskCreditEstimateService = taskCreditEstimateService;
        this.taskCreditDispatchService = taskCreditDispatchService;
        this.communityEventMapper = communityEventMapper;
        this.communityPostMapper = communityPostMapper;
    }

    @Override
    @Transactional
    public TaskStatusResponse create(Long userId, CreateTaskRequest request) {
        return taskMapper.findByUserIdAndIdempotencyKey(userId, request.clientRequestId())
                .map(TaskStatusResponse::from)
                .orElseGet(() -> createNewTask(userId, request.toolCode(), request.params(), request.clientRequestId(), request.sourcePostId(), true));
    }

    @Override
    @Transactional
    public TaskStatusResponse createForAgentTool(Long userId, CreateTaskRequest request) {
        return taskMapper.findByUserIdAndIdempotencyKey(userId, request.clientRequestId())
                .map(TaskStatusResponse::from)
                .orElseGet(() -> {
                    AiTool tool = toolMapper.findOnlineByCode(request.toolCode())
                            .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
                    AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(tool);
                    taskCreditDispatchService.ensureDispatchAllowed(userId, tool, modelConfig);
                    return createNewTask(
                            userId,
                            request.toolCode(),
                            request.params(),
                            request.clientRequestId(),
                            request.sourcePostId(),
                            true
                    );
                });
    }

    @Override
    public TaskStatusResponse status(Long userId, Long taskId) {
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    @Override
    public TaskDetailResponse detail(Long userId, Long taskId) {
        return toDetail(findTask(taskId, userId));
    }

    @Override
    public PageResponse<TaskDetailResponse> list(Long userId, String status, String toolCode, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<TaskDetailResponse> tasks = taskMapper
                .findByUserId(userId, status, toolCode, normalizedPageSize, offset)
                .stream()
                .map(this::toDetail)
                .toList();
        long total = taskMapper.countByUserId(userId, status, toolCode);
        return PageResponse.of(tasks, total, pageNo, pageSize);
    }

    @Override
    @Transactional
    public TaskStatusResponse cancel(Long userId, Long taskId) {
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
    }

    @Override
    @Transactional
    public TaskStatusResponse regenerate(Long userId, Long taskId, RegenerateTaskRequest request) {
        AiTask originalTask = findTask(taskId, userId);
        return taskMapper.findByUserIdAndIdempotencyKey(userId, request.clientRequestId())
                .map(TaskStatusResponse::from)
                .orElseGet(() -> createNewTask(userId, originalTask.getToolCode(), request.params(), request.clientRequestId(), null, true));
    }

    @Override
    public PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId, Long taskId,
                                                      Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<TaskDetailResponse> tasks = taskMapper.findForAdmin(status, toolCode, userId, taskId, normalizedPageSize, offset).stream()
                .map(this::toDetail)
                .toList();
        long total = taskMapper.countForAdmin(status, toolCode, userId, taskId);
        return PageResponse.of(tasks, total, pageNo, pageSize);
    }

    @Override
    public TaskDetailResponse adminDetail(Long taskId) {
        return toDetail(findTask(taskId));
    }

    @Override
    @Transactional
    public TaskStatusResponse adminRetry(Long taskId) {
        AiTask task = findTask(taskId);
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

    private TaskStatusResponse createNewTask(Long userId, String toolCode, JsonNode params, String clientRequestId,
                                             Long sourcePostId, boolean chargeTaskCredits) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        AgentModelConfig modelConfig = modelCapabilityService.resolveModelConfigForTool(tool);
        modelCapabilityService.validateExecution(tool, modelConfig);
        if (chargeTaskCredits) {
            taskCreditDispatchService.ensureDispatchAllowed(userId, tool, modelConfig);
        }

        JsonNode normalizedParams = normalizeTaskParams(params);
        AiTask task = new AiTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setToolId(tool.getId());
        task.setParamsJson(normalizedParams.toString());
        task.setIdempotencyKey(clientRequestId);
        int estimatedCredits = chargeTaskCredits ? taskCreditEstimateService.estimateTaskCredits(tool, modelConfig) : 0;
        task.setEstimatedCreditCost(estimatedCredits);

        Long taskId = taskMapper.insertTask(task);
        if (chargeTaskCredits) {
            creditService.freeze(userId, CreditSourceType.TASK, taskId, estimatedCredits);
        }
        if (sourcePostId != null) {
            communityEventMapper.insertEvent(sourcePostId, userId, "task_created", "dashboard", tool.getToolCode(), taskId, 0);
        }
        taskOutboxService.enqueueTaskCreated(taskId);
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    private TaskDetailResponse toDetail(AiTask task) {
        TaskResultResponse result = taskMapper.findFirstResult(task.getId()).orElse(null);
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

    private AgentTaskSourceResponse toAgentTaskSource(AgentToolCall call) {
        return new AgentTaskSourceResponse(call.getRunId(), call.getId(), call.getToolCode());
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

    private JsonNode normalizeTaskParams(JsonNode params) {
        if (params == null || !params.isObject()) {
            return params == null ? objectMapper.createObjectNode() : params;
        }
        ObjectNode normalized = ((ObjectNode) params).deepCopy();
        JsonNode aspectRatio = firstTextual(normalized.get("aspectRatio"), normalized.get("aspect_ratio"), normalized.get("imageRatio"));
        if (aspectRatio != null && !normalized.hasNonNull("aspectRatio")) {
            normalized.set("aspectRatio", aspectRatio);
        }
        return normalized;
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
