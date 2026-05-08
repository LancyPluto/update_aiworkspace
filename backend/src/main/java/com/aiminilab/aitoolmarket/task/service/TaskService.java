package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;

public interface TaskService {
    TaskStatusResponse create(Long userId, CreateTaskRequest request);

    TaskStatusResponse status(Long userId, Long taskId);

    TaskDetailResponse detail(Long userId, Long taskId);

    PageResponse<TaskDetailResponse> list(Long userId);

    PageResponse<TaskDetailResponse> adminList(String status, String toolCode, Long userId);

    TaskDetailResponse adminDetail(Long taskId);

    TaskStatusResponse adminRetry(Long taskId);

    TaskStatusResponse adminCancel(Long taskId);
}
