package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
<<<<<<< HEAD
import com.aiminilab.aitoolmarket.task.dto.CreditLogResponse;
=======
import com.aiminilab.aitoolmarket.task.dto.RegenerateTaskRequest;
>>>>>>> origin/feature/backend-core
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskLogResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskResultResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class TaskServiceImpl implements TaskService {

    private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final CreditService creditService;
    private final CreditMapper creditMapper;
    private final ObjectMapper objectMapper;
    private final TaskQueuePublisher taskQueuePublisher;

<<<<<<< HEAD
    public TaskServiceImpl(TaskMapper taskMapper,
                           ToolMapper toolMapper,
                           CreditService creditService,
                           CreditMapper creditMapper,
                           ObjectMapper objectMapper) {
=======
    public TaskServiceImpl(
            TaskMapper taskMapper,
            ToolMapper toolMapper,
            CreditService creditService,
            ObjectMapper objectMapper,
            TaskQueuePublisher taskQueuePublisher
    ) {
>>>>>>> origin/feature/backend-core
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.creditService = creditService;
        this.creditMapper = creditMapper;
        this.objectMapper = objectMapper;
        this.taskQueuePublisher = taskQueuePublisher;
    }

    @Override
    @Transactional
    public TaskStatusResponse create(Long userId, CreateTaskRequest request) {
<<<<<<< HEAD
        AiTool tool = toolMapper.findOnlineByCode(request.toolCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));

        AiTask task = new AiTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setToolId(tool.getId());
        task.setParamsJson(request.params().toString());
        task.setIdempotencyKey(request.clientRequestId());
        task.setEstimatedCreditCost(tool.getEstimatedCreditCost());

        Long taskId = taskMapper.insert(task);
        creditService.deductForTask(userId, taskId, tool.getEstimatedCreditCost());
        taskMapper.insertLog(taskId, "TASK_CREATED", null, "QUEUED", "任务已创建并入队", "USER", userId);
        return status(userId, taskId);
=======
        return taskMapper.findByUserIdAndIdempotencyKey(userId, request.clientRequestId())
                .map(TaskStatusResponse::from)
                .orElseGet(() -> createNewTask(userId, request.toolCode(), request.params(), request.clientRequestId()));
>>>>>>> origin/feature/backend-core
    }

    @Override
    public TaskStatusResponse status(Long userId, Long taskId) {
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    @Override
    public TaskDetailResponse detail(Long userId, Long taskId) {
        return toDetail(findTask(taskId, userId), false);
    }

    @Override
<<<<<<< HEAD
    public PageResponse<TaskDetailResponse> list(Long userId) {
        List<TaskDetailResponse> tasks = taskMapper.findByUserId(userId).stream()
                .map(task -> toDetail(task, false))
=======
    public PageResponse<TaskDetailResponse> list(Long userId, String status, String toolCode, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<TaskDetailResponse> tasks = taskMapper
                .findByUserId(userId, status, toolCode, normalizedPageSize, offset)
                .stream()
                .map(this::toDetail)
>>>>>>> origin/feature/backend-core
                .toList();
        long total = taskMapper.countByUserId(userId, status, toolCode);
        return PageResponse.of(tasks, total, pageNo, pageSize);
    }

    @Override
<<<<<<< HEAD
    public PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId) {
        List<TaskDetailResponse> tasks = taskMapper.findForAdmin(status, toolCode, userId).stream()
                .map(task -> toDetail(task, false))
=======
    @Transactional
    public TaskStatusResponse cancel(Long userId, Long taskId) {
        AiTask task = findTask(taskId, userId);
        ensureCancellable(task);
        creditService.releaseForTask(task.getUserId(), taskId, task.getEstimatedCreditCost());
        taskMapper.cancel(taskId);
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    @Override
    @Transactional
    public TaskStatusResponse regenerate(Long userId, Long taskId, RegenerateTaskRequest request) {
        AiTask originalTask = findTask(taskId, userId);
        return taskMapper.findByUserIdAndIdempotencyKey(userId, request.clientRequestId())
                .map(TaskStatusResponse::from)
                .orElseGet(() -> createNewTask(userId, originalTask.getToolCode(), request.params(), request.clientRequestId()));
    }

    @Override
    public PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId,
                                                      Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<TaskDetailResponse> tasks = taskMapper.findForAdmin(status, toolCode, userId, normalizedPageSize, offset).stream()
                .map(this::toDetail)
>>>>>>> origin/feature/backend-core
                .toList();
        long total = taskMapper.countForAdmin(status, toolCode, userId);
        return PageResponse.of(tasks, total, pageNo, pageSize);
    }

    @Override
    public TaskDetailResponse adminDetail(Long taskId) {
        return toDetail(findTask(taskId), true);
    }

    @Override
    public TaskStatusResponse adminRetry(Long taskId) {
        AiTask before = findTask(taskId);
        taskMapper.resetToQueued(taskId);
<<<<<<< HEAD
        taskMapper.insertLog(taskId, "TASK_RETRY", before.getStatus(), "QUEUED", "管理员重试任务", "ADMIN", null);
=======
        publishAfterCommit(taskId);
>>>>>>> origin/feature/backend-core
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    public TaskStatusResponse adminCancel(Long taskId) {
<<<<<<< HEAD
        AiTask before = findTask(taskId);
=======
        AiTask task = findTask(taskId);
        if (!TaskStatus.SUCCESS.name().equals(task.getStatus()) && !TaskStatus.FAILED.name().equals(task.getStatus())
                && !TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            creditService.releaseForTask(task.getUserId(), taskId, task.getEstimatedCreditCost());
        }
>>>>>>> origin/feature/backend-core
        taskMapper.cancel(taskId);
        taskMapper.insertLog(taskId, "TASK_CANCEL", before.getStatus(), "CANCELLED", "管理员取消任务", "ADMIN", null);
        return TaskStatusResponse.from(findTask(taskId));
    }

<<<<<<< HEAD
    private TaskDetailResponse toDetail(AiTask task, boolean withLogs) {
=======
    private TaskStatusResponse createNewTask(Long userId, String toolCode, JsonNode params, String clientRequestId) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));

        AiTask task = new AiTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setToolId(tool.getId());
        task.setParamsJson(params.toString());
        task.setIdempotencyKey(clientRequestId);
        task.setEstimatedCreditCost(tool.getEstimatedCreditCost());

        Long taskId = taskMapper.insertTask(task);
        creditService.freezeForTask(userId, taskId, tool.getEstimatedCreditCost());
        publishAfterCommit(taskId);
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    private void ensureCancellable(AiTask task) {
        String status = task.getStatus();
        if (TaskStatus.SUCCESS.name().equals(status) || TaskStatus.FAILED.name().equals(status)
                || TaskStatus.CANCELLED.name().equals(status)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "当前任务状态不允许取消");
        }
    }

    private TaskDetailResponse toDetail(AiTask task) {
>>>>>>> origin/feature/backend-core
        TaskResultResponse result = taskMapper.findFirstResult(task.getId()).orElse(null);
        Integer consumed = creditMapper.findConsumedByTaskId(task.getId());
        List<TaskLogResponse> logs = withLogs ? taskMapper.findLogs(task.getId()) : Collections.emptyList();
        List<CreditLogResponse> creditLogs = withLogs ? creditMapper.findLogsByTaskId(task.getId()) : Collections.emptyList();
        return TaskDetailResponse.of(task, parseParams(task.getParamsJson()), result, consumed, logs, creditLogs);
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

    private String generateTaskNo() {
        String date = TASK_NO_DATE.format(LocalDate.now());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "T" + date + suffix;
    }

    private void publishAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskQueuePublisher.publish(taskId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskQueuePublisher.publish(taskId);
            }
        });
    }
}
