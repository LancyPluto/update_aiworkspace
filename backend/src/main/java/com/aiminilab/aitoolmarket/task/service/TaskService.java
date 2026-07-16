package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.EstimateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.RegenerateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.StaleTaskReconcileResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskEstimateResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;

public interface TaskService {
    TaskStatusResponse create(Long userId, CreateTaskRequest request);

    TaskStatusResponse createForAgentTool(Long userId, CreateTaskRequest request);

    TaskStatusResponse createForAgentTool(Long userId, CreateTaskRequest request, int excludeFrozen);

    TaskStatusResponse createWorkflowRoot(Long userId, String toolCode, JsonNode params, String clientRequestId);

    TaskEstimateResponse estimate(Long userId, EstimateTaskRequest request);

    TaskStatusResponse status(Long userId, Long taskId);

    TaskDetailResponse detail(Long userId, Long taskId);

    PageResponse<TaskDetailResponse> list(Long userId, String status, String toolCode, Integer pageNo, Integer pageSize);

    TaskStatusResponse cancel(Long userId, Long taskId);

    void delete(Long userId, Long taskId);

    TaskStatusResponse regenerate(Long userId, Long taskId, RegenerateTaskRequest request);

    PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId, Long taskId, Integer pageNo, Integer pageSize);

    TaskDetailResponse adminDetail(Long taskId);

    TaskStatusResponse adminRetry(Long taskId);

    TaskStatusResponse adminCancel(Long taskId);

    StaleTaskReconcileResponse adminReconcileStaleTasks(Integer staleMinutes, Integer limit);
}
