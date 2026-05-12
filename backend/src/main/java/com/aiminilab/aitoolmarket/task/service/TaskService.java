package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.RegenerateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;

public interface TaskService {
    TaskStatusResponse create(Long userId, CreateTaskRequest request);

    TaskStatusResponse status(Long userId, Long taskId);

    TaskDetailResponse detail(Long userId, Long taskId);

    PageResponse<TaskDetailResponse> list(Long userId, String status, String toolCode, Integer pageNo, Integer pageSize);

    TaskStatusResponse cancel(Long userId, Long taskId);

    TaskStatusResponse regenerate(Long userId, Long taskId, RegenerateTaskRequest request);

    PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId, Integer pageNo, Integer pageSize);

    TaskDetailResponse adminDetail(Long taskId);

    TaskStatusResponse adminRetry(Long taskId);

    TaskStatusResponse adminCancel(Long taskId);
}
