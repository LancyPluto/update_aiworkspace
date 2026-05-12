package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/workspaces")
public class AgentWorkspaceController {

    private final AgentWorkspaceService agentWorkspaceService;

    public AgentWorkspaceController(AgentWorkspaceService agentWorkspaceService) {
        this.agentWorkspaceService = agentWorkspaceService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentWorkspaceService.WorkspaceResponse>> list() {
        return ApiResponse.success(agentWorkspaceService.list(AuthContext.get().userId()));
    }

    @GetMapping("/{workspaceId}/memory")
    public ApiResponse<PageResponse<AgentWorkspaceMemoryItemResponse>> listMemory(@PathVariable Long workspaceId) {
        return ApiResponse.success(agentWorkspaceService.listMemory(AuthContext.get().userId(), workspaceId));
    }

    @PostMapping("/{workspaceId}/memory")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> createMemory(@PathVariable Long workspaceId,
                                                                      @Valid @RequestBody CreateAgentWorkspaceMemoryRequest request) {
        return ApiResponse.success(agentWorkspaceService.createMemory(AuthContext.get().userId(), workspaceId, request));
    }

    @PutMapping("/{workspaceId}/memory/{memoryId}")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> updateMemory(@PathVariable Long workspaceId,
                                                                      @PathVariable Long memoryId,
                                                                      @Valid @RequestBody UpdateAgentWorkspaceMemoryRequest request) {
        return ApiResponse.success(agentWorkspaceService.updateMemory(AuthContext.get().userId(), workspaceId, memoryId, request));
    }

    @DeleteMapping("/{workspaceId}/memory/{memoryId}")
    public ApiResponse<Void> deleteMemory(@PathVariable Long workspaceId, @PathVariable Long memoryId) {
        agentWorkspaceService.deleteMemory(AuthContext.get().userId(), workspaceId, memoryId);
        return ApiResponse.success(null);
    }
}
