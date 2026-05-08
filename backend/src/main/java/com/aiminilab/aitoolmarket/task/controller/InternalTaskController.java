package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/tasks")
public class InternalTaskController {

    private final InternalTaskService internalTaskService;

    public InternalTaskController(InternalTaskService internalTaskService) {
        this.internalTaskService = internalTaskService;
    }

    @GetMapping("/{taskId}/execution-context")
    public ApiResponse<ExecutionContextResponse> executionContext(@PathVariable Long taskId) {
        return ApiResponse.success(internalTaskService.executionContext(taskId));
    }

    @PostMapping("/{taskId}/processing")
    public ApiResponse<TaskStatusResponse> processing(@PathVariable Long taskId,
                                                       @RequestBody WorkerProcessingRequest request) {
        return ApiResponse.success(internalTaskService.markProcessing(taskId, request));
    }

    @PostMapping("/{taskId}/success")
    public ApiResponse<TaskStatusResponse> success(@PathVariable Long taskId,
                                                   @Valid @RequestBody WorkerSuccessRequest request) {
        return ApiResponse.success(internalTaskService.markSuccess(taskId, request));
    }

    @PostMapping("/{taskId}/failed")
    public ApiResponse<TaskStatusResponse> failed(@PathVariable Long taskId,
                                                  @RequestBody WorkerFailedRequest request) {
        return ApiResponse.success(internalTaskService.markFailed(taskId, request));
    }
}
