package com.aiminilab.aitoolmarket.workflow.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowFeedbackRequest;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class WorkflowFeedbackController {

    private final WorkflowExecutionService workflowExecutionService;
    private final TaskService taskService;

    public WorkflowFeedbackController(WorkflowExecutionService workflowExecutionService,
                                      TaskService taskService) {
        this.workflowExecutionService = workflowExecutionService;
        this.taskService = taskService;
    }

    @PostMapping("/{taskId}/workflow-feedback")
    public ApiResponse<TaskStatusResponse> submitFeedback(@PathVariable Long taskId,
                                                          @Valid @RequestBody WorkflowFeedbackRequest request) {
        Long userId = AuthContext.get().userId();
        workflowExecutionService.submitUserFeedback(taskId, userId, request.fields());
        return ApiResponse.success(taskService.status(userId, taskId));
    }
}
