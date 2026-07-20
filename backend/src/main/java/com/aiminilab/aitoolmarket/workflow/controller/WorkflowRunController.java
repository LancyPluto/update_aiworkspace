package com.aiminilab.aitoolmarket.workflow.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunRequest;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunResponse;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunDetailResponse;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowConfirmationRequest;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowToolDetailResponse;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowToolPageResponse;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowToolQueryService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowInteractionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/agents")
public class WorkflowRunController {

    private final WorkflowToolQueryService queryService;
    private final WorkflowRunApplicationService runApplicationService;
    private final WorkflowInteractionService interactionService;

    public WorkflowRunController(WorkflowToolQueryService queryService,
                                 WorkflowRunApplicationService runApplicationService,
                                 WorkflowInteractionService interactionService) {
        this.queryService = queryService;
        this.runApplicationService = runApplicationService;
        this.interactionService = interactionService;
    }

    @GetMapping("/tools")
    public ApiResponse<WorkflowToolPageResponse> listTools(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return ApiResponse.success(queryService.list(page, pageSize, keyword, category));
    }

    @GetMapping("/tools/{toolCode}")
    public ApiResponse<WorkflowToolDetailResponse> toolDetail(@PathVariable String toolCode) {
        return ApiResponse.success(queryService.detail(toolCode));
    }

    @PostMapping("/tools/{toolCode}/runs")
    public ApiResponse<CreateWorkflowRunResponse> createRun(@PathVariable String toolCode,
                                                            @Valid @RequestBody CreateWorkflowRunRequest request) {
        WorkflowRunCreated created = runApplicationService.create(new CreateWorkflowRunCommand(
                AuthContext.get().userId(),
                toolCode,
                request.input(),
                request.clientRequestId(),
                "AGENTS_PAGE",
                null
        ));
        return ApiResponse.success(CreateWorkflowRunResponse.from(created));
    }

    @GetMapping("/runs/{rootTaskId}")
    public ApiResponse<WorkflowRunDetailResponse> runDetail(@PathVariable Long rootTaskId) {
        return ApiResponse.success(queryService.runDetail(rootTaskId, AuthContext.get().userId()));
    }

    @PostMapping("/runs/{rootTaskId}/feedback")
    public ApiResponse<WorkflowRunDetailResponse> confirm(@PathVariable Long rootTaskId,
                                                          @Valid @RequestBody WorkflowConfirmationRequest request) {
        Long userId = AuthContext.get().userId();
        interactionService.confirm(rootTaskId, userId, request);
        return ApiResponse.success(queryService.runDetail(rootTaskId, userId));
    }

    @PostMapping("/runs/{rootTaskId}/resume")
    public ApiResponse<WorkflowRunDetailResponse> resume(@PathVariable Long rootTaskId) {
        Long userId = AuthContext.get().userId();
        interactionService.resume(rootTaskId, userId);
        return ApiResponse.success(queryService.runDetail(rootTaskId, userId));
    }

    @PostMapping("/runs/{rootTaskId}/cancel")
    public ApiResponse<WorkflowRunDetailResponse> cancel(@PathVariable Long rootTaskId) {
        Long userId = AuthContext.get().userId();
        interactionService.cancel(rootTaskId, userId, "USER_CANCELLED");
        return ApiResponse.success(queryService.runDetail(rootTaskId, userId));
    }
}
