package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<TaskStatusResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(taskService.create(AuthContext.get().userId(), request));
    }

    @GetMapping("/{taskId}/status")
    public ApiResponse<TaskStatusResponse> status(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.status(AuthContext.get().userId(), taskId));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.detail(AuthContext.get().userId(), taskId));
    }

    @GetMapping
    public ApiResponse<PageResponse<TaskDetailResponse>> list() {
        return ApiResponse.success(taskService.list(AuthContext.get().userId()));
    }
}
