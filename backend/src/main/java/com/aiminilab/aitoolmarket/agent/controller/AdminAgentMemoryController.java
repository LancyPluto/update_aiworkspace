package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentMemoryQuery;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentMemoryUpdateRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/agent/memory")
public class AdminAgentMemoryController {

    private final AgentWorkspaceService agentWorkspaceService;

    public AdminAgentMemoryController(AgentWorkspaceService agentWorkspaceService) {
        this.agentWorkspaceService = agentWorkspaceService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentWorkspaceMemoryItemResponse>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String memoryType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize
    ) {
        return ApiResponse.success(agentWorkspaceService.adminListMemory(
                new AdminAgentMemoryQuery(userId, workspaceId, status, memoryType, keyword, pageNo, pageSize)
        ));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AdminAgentMemoryUpdateRequest request
    ) {
        return ApiResponse.success(agentWorkspaceService.adminUpdateMemory(id, request));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> approve(@PathVariable Long id) {
        return ApiResponse.success(agentWorkspaceService.adminApproveMemory(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> reject(@PathVariable Long id) {
        return ApiResponse.success(agentWorkspaceService.adminRejectMemory(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentWorkspaceService.adminDeleteMemory(id);
        return ApiResponse.success(null);
    }
}
