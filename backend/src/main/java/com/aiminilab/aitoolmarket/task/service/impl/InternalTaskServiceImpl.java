package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InternalTaskServiceImpl implements InternalTaskService {

    private final TaskMapper taskMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;
    private final TaskMetrics taskMetrics;

    public InternalTaskServiceImpl(TaskMapper taskMapper, AgentModelConfigMapper agentModelConfigMapper,
                                   ToolFieldItemMapper toolFieldItemMapper, ObjectMapper objectMapper,
                                   CreditService creditService, TaskMetrics taskMetrics) {
        this.taskMapper = taskMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.objectMapper = objectMapper;
        this.creditService = creditService;
        this.taskMetrics = taskMetrics;
    }

    @Override
    public ExecutionContextResponse executionContext(Long taskId) {
        AiTask task = findTask(taskId);
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(task.getToolId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        AgentModelConfig modelConfig = agentModelConfigMapper.findForToolExecution(task.getToolId());
        return ExecutionContextResponse.of(task, parseParams(task.getParamsJson()),
                ExecutionModelConfigResponse.from(modelConfig), fields);
    }

    @Override
    public TaskStatusResponse markProcessing(Long taskId, WorkerProcessingRequest request) {
        int progress = request.progress() == null ? 10 : Math.max(0, Math.min(99, request.progress()));
        String message = request.progressMessage() == null || request.progressMessage().isBlank()
                ? "AI is processing"
                : request.progressMessage();
        AiTask task = findTask(taskId);
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.PROCESSING.name());
        if (taskMapper.markProcessing(taskId, progress, message, List.of(TaskStatus.QUEUED.name())) == 0) {
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
        creditService.settleForTask(task.getUserId(), taskId, task.getEstimatedCreditCost());
        taskMapper.insertResult(taskId, task.getUserId(), request.resourceType(), request.contentText());
        taskMetrics.recordTaskOutcome(task.getToolCode(), "SUCCESS", task.getCreatedAt(), findTask(taskId).getFinishedAt());
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
        AiTask task = findTask(taskId);
        if (TaskStatus.FAILED.name().equals(task.getStatus()) || TaskStatus.SUCCESS.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return TaskStatusResponse.from(task);
        }
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.FAILED.name());
        int updated = taskMapper.markFailed(taskId, errorCode, errorMessage, List.of(TaskStatus.PROCESSING.name()));
        if (updated == 0) {
            AiTask current = findTask(taskId);
            if (TaskStatus.FAILED.name().equals(current.getStatus()) || TaskStatus.SUCCESS.name().equals(current.getStatus())
                    || TaskStatus.CANCELLED.name().equals(current.getStatus())) {
                return TaskStatusResponse.from(current);
            }
            TaskStateMachine.ensureTransition(current.getStatus(), TaskStatus.FAILED.name());
        }
        creditService.releaseForTask(task.getUserId(), taskId, task.getEstimatedCreditCost());
        taskMetrics.recordTaskOutcome(task.getToolCode(), "FAILED", task.getCreatedAt(), findTask(taskId).getFinishedAt());
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
