package com.aiminilab.aitoolmarket.ppt.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptAdminWorkflowDetailResponse;
import com.aiminilab.aitoolmarket.ppt.dto.UpsertPptWorkflowRequest;
import com.aiminilab.aitoolmarket.ppt.service.PptAdminWorkflowService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/tools")
public class AdminPptWorkflowController {

    private final PptAdminWorkflowService pptAdminWorkflowService;

    public AdminPptWorkflowController(PptAdminWorkflowService pptAdminWorkflowService) {
        this.pptAdminWorkflowService = pptAdminWorkflowService;
    }

    @GetMapping("/{toolId}/ppt-workflow")
    public ApiResponse<PptAdminWorkflowDetailResponse> getWorkflow(@PathVariable Long toolId) {
        return ApiResponse.success(pptAdminWorkflowService.getWorkflowDetail(toolId));
    }

    @PutMapping("/{toolId}/ppt-workflow")
    public ApiResponse<PptAdminWorkflowDetailResponse> updateWorkflow(@PathVariable Long toolId,
                                                                        @Valid @RequestBody UpsertPptWorkflowRequest request) {
        return ApiResponse.success(pptAdminWorkflowService.updateWorkflow(
                toolId,
                request.workflow(),
                AuthContext.get().userId()
        ));
    }

    @PostMapping("/{toolId}/ppt-workflow/sync-engine")
    public ApiResponse<PptAdminWorkflowDetailResponse> syncEngine(@PathVariable Long toolId) {
        return ApiResponse.success(pptAdminWorkflowService.syncEngineSettings(toolId));
    }
}
