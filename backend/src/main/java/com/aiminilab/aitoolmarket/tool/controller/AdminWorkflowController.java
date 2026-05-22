package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowVersionItemResponse;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/tools/{toolId}/workflow")
public class AdminWorkflowController {

    private final WorkflowService workflowService;

    public AdminWorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public ApiResponse<WorkflowResponse> get(@PathVariable Long toolId) {
        WorkflowResponse workflow = workflowService.getWorkflow(toolId);
        return ApiResponse.success(workflow);
    }

    @PutMapping
    public ApiResponse<WorkflowResponse> save(@PathVariable Long toolId,
                                               @Valid @RequestBody UpsertWorkflowRequest request) {
        Long operatorId = AuthContext.get().userId();
        WorkflowResponse saved = workflowService.saveWorkflow(toolId, request, operatorId);
        return ApiResponse.success(saved);
    }

    @GetMapping("/versions")
    public ApiResponse<List<WorkflowVersionItemResponse>> versions(
            @PathVariable Long toolId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        WorkflowResponse workflow = workflowService.getWorkflow(toolId);
        if (workflow == null) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(workflowService.listVersions(workflow.id(), pageNo, pageSize));
    }

    @PostMapping("/versions/{version}/restore")
    public ApiResponse<WorkflowResponse> restoreVersion(
            @PathVariable Long toolId,
            @PathVariable int version) {
        WorkflowResponse workflow = workflowService.getWorkflow(toolId);
        if (workflow == null) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "Workflow not found");
        }
        Long operatorId = AuthContext.get().userId();
        WorkflowResponse restored = workflowService.restoreVersion(workflow.id(), version, operatorId);
        return ApiResponse.success(restored);
    }
}
