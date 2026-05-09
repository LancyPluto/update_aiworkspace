package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InternalTaskServiceImpl implements InternalTaskService {

    private final TaskMapper taskMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;

    public InternalTaskServiceImpl(TaskMapper taskMapper, ToolFieldItemMapper toolFieldItemMapper, ObjectMapper objectMapper,
                                   CreditService creditService) {
        this.taskMapper = taskMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.objectMapper = objectMapper;
        this.creditService = creditService;
    }

    @Override
    public ExecutionContextResponse executionContext(Long taskId) {
        AiTask task = findTask(taskId);
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(task.getToolId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        return ExecutionContextResponse.of(task, parseParams(task.getParamsJson()), fields);
    }

    @Override
    public TaskStatusResponse markProcessing(Long taskId, WorkerProcessingRequest request) {
        int progress = request.progress() == null ? 10 : Math.max(0, Math.min(99, request.progress()));
        String message = request.progressMessage() == null || request.progressMessage().isBlank()
                ? "AI is processing"
                : request.progressMessage();
        AiTask before = findTask(taskId);
        taskMapper.markProcessing(taskId, progress, message);
        taskMapper.insertLog(taskId, "TASK_PROCESSING", before.getStatus(), "PROCESSING", message, "WORKER", null);
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    @Transactional
    public TaskStatusResponse markSuccess(Long taskId, WorkerSuccessRequest request) {
        AiTask task = findTask(taskId);
        if (TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            return TaskStatusResponse.from(task);
        }
        creditService.settleForTask(task.getUserId(), taskId, task.getEstimatedCreditCost());
        taskMapper.insertResult(taskId, task.getUserId(), request.resourceType(), request.contentText());
        taskMapper.markSuccess(taskId);
        taskMapper.insertLog(taskId, "TASK_SUCCESS", task.getStatus(), "SUCCESS", "Worker 回写成功结果", "WORKER", null);
        return TaskStatusResponse.from(findTask(taskId));
    }

    @Override
    @Transactional
    public TaskStatusResponse markFailed(Long taskId, WorkerFailedRequest request) {
        String errorCode = request.errorCode() == null || request.errorCode().isBlank()
                ? ErrorCode.MODEL_CALL_FAILED.name()
                : request.errorCode();
        String errorMessage = request.errorMessage() == null || request.errorMessage().isBlank()
                ? "Worker execution failed"
                : request.errorMessage();
<<<<<<< HEAD
        AiTask before = findTask(taskId);
=======
        AiTask task = findTask(taskId);
        if (!TaskStatus.SUCCESS.name().equals(task.getStatus()) && !TaskStatus.FAILED.name().equals(task.getStatus())
                && !TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            creditService.releaseForTask(task.getUserId(), taskId, task.getEstimatedCreditCost());
        }
>>>>>>> origin/feature/backend-core
        taskMapper.markFailed(taskId, errorCode, errorMessage);
        taskMapper.insertLog(taskId, "TASK_FAILED", before.getStatus(), "FAILED", errorMessage, "WORKER", null);
        return TaskStatusResponse.from(findTask(taskId));
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
}
