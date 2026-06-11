package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowValidationResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowVersionItemResponse;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslValidationResult;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowDslService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/tools/{toolId}/workflow")
public class AdminWorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowDslService workflowDslService;

    public AdminWorkflowController(WorkflowService workflowService,
                                   WorkflowDslService workflowDslService) {
        this.workflowService = workflowService;
        this.workflowDslService = workflowDslService;
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

    /** 校验当前已保存的工作流 DAG（不修改任何数据）。 */
    @PostMapping("/validate")
    public ApiResponse<WorkflowValidationResponse> validate(@PathVariable Long toolId) {
        WorkflowResponse workflow = workflowService.getWorkflow(toolId);
        if (workflow == null) {
            return ApiResponse.success(new WorkflowValidationResponse(false, List.of("工作流不存在，请先保存画布")));
        }
        WorkflowDslValidationResult result = workflowDslService.validate(
                workflow.nodesJson(), workflow.edgesJson(), workflow.configJson());
        return ApiResponse.success(new WorkflowValidationResponse(result.valid(), result.errors()));
    }

    /** 校验通过后将工作流置为 PUBLISHED；运行端（WorkflowExecutionService）只执行 PUBLISHED 工作流。 */
    @PostMapping("/publish")
    public ApiResponse<WorkflowResponse> publish(@PathVariable Long toolId) {
        WorkflowResponse workflow = workflowService.getWorkflow(toolId);
        if (workflow == null) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "工作流不存在，请先保存画布");
        }
        WorkflowDslValidationResult result = workflowDslService.validate(
                workflow.nodesJson(), workflow.edgesJson(), workflow.configJson());
        if (!result.valid()) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "工作流校验未通过：" + String.join("；", result.errors()));
        }
        Long operatorId = AuthContext.get().userId();
        return ApiResponse.success(workflowService.updateStatus(workflow.id(), "PUBLISHED", operatorId));
    }

    /** 将工作流退回 DRAFT，运行端会回退到工具原有的执行 handler。 */
    @PostMapping("/unpublish")
    public ApiResponse<WorkflowResponse> unpublish(@PathVariable Long toolId) {
        WorkflowResponse workflow = workflowService.getWorkflow(toolId);
        if (workflow == null) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "工作流不存在");
        }
        Long operatorId = AuthContext.get().userId();
        return ApiResponse.success(workflowService.updateStatus(workflow.id(), "DRAFT", operatorId));
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
