package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskResultResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final CreditService creditService;
    private final ObjectMapper objectMapper;

    public TaskServiceImpl(TaskMapper taskMapper, ToolMapper toolMapper, CreditService creditService, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TaskStatusResponse create(Long userId, CreateTaskRequest request) {
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
        return status(userId, taskId);
    }

    @Override
    public TaskStatusResponse status(Long userId, Long taskId) {
        return TaskStatusResponse.from(findTask(taskId, userId));
    }

    @Override
    public TaskDetailResponse detail(Long userId, Long taskId) {
        AiTask task = findTask(taskId, userId);
        return toDetail(task);
    }

    @Override
    public PageResponse<TaskDetailResponse> list(Long userId) {
        List<TaskDetailResponse> tasks = taskMapper.findByUserId(userId).stream()
                .map(this::toDetail)
                .toList();
        return new PageResponse<>(tasks, tasks.size());
    }

    private TaskDetailResponse toDetail(AiTask task) {
        TaskResultResponse result = taskMapper.findFirstResult(task.getId()).orElse(null);
        return TaskDetailResponse.of(task, parseParams(task.getParamsJson()), result);
    }

    private AiTask findTask(Long taskId, Long userId) {
        return taskMapper.findByIdAndUserId(taskId, userId)
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
}
