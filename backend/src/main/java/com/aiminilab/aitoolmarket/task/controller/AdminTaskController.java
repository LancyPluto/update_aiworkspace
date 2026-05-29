package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/tasks")
public class AdminTaskController {

    private final TaskService taskService;

    public AdminTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<PageResponse<TaskDetailResponse>> list(@RequestParam(required = false) String status,
                                                              @RequestParam(required = false) String toolCode,
                                                              @RequestParam(required = false) Long userId,
                                                              @RequestParam(required = false) Long taskId,
                                                              @RequestParam(required = false) Integer pageNo,
                                                              @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(taskService.adminList(status, toolCode, userId, taskId, pageNo, pageSize));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.adminDetail(taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskStatusResponse> retry(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.adminRetry(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskStatusResponse> cancel(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.adminCancel(taskId));
    }
}
