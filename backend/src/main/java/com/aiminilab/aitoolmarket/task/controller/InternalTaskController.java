package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskResponse;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.InternalCreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/tasks")
public class InternalTaskController {

    private final InternalTaskService internalTaskService;
    private final TaskService taskService;

    public InternalTaskController(InternalTaskService internalTaskService, TaskService taskService) {
        this.internalTaskService = internalTaskService;
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<TaskStatusResponse> createForAgent(@Valid @RequestBody InternalCreateTaskRequest request) {
        int excludeFrozen = request.excludeFrozen() == null ? 0 : Math.max(0, request.excludeFrozen());
        return ApiResponse.success(taskService.createForAgentTool(
                request.userId(),
                new CreateTaskRequest(request.toolCode(), request.params(), request.clientRequestId(), null, request.modelConfigId()),
                excludeFrozen
        ));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailResponse> detailForAgent(@PathVariable Long taskId, @RequestParam Long userId) {
        return ApiResponse.success(taskService.detail(userId, taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskStatusResponse> cancelForAgent(@PathVariable Long taskId, @RequestParam Long userId) {
        return ApiResponse.success(taskService.cancel(userId, taskId));
    }

    @GetMapping("/{taskId}/execution-context")
    public ApiResponse<ExecutionContextResponse> executionContext(@PathVariable Long taskId) {
        return ApiResponse.success(internalTaskService.executionContext(taskId));
    }

    @PostMapping("/{taskId}/claim")
    public ApiResponse<ClaimTaskResponse> claim(@PathVariable Long taskId,
                                                @RequestBody ClaimTaskRequest request) {
        return ApiResponse.success(internalTaskService.claim(taskId, request));
    }

    @PostMapping("/{taskId}/lease/renew")
    public ApiResponse<ClaimTaskResponse> renewLease(@PathVariable Long taskId,
                                                     @RequestBody ClaimTaskRequest request) {
        return ApiResponse.success(internalTaskService.renewLease(taskId, request));
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
