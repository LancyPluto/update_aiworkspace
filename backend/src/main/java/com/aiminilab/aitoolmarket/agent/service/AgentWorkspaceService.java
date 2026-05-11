package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

import java.time.LocalDateTime;

public interface AgentWorkspaceService {
    PageResponse<WorkspaceResponse> list(Long userId);

    Long resolveWorkspaceId(Long userId, Long requestedWorkspaceId);

    PageResponse<AgentWorkspaceMemoryItemResponse> listMemory(Long userId, Long workspaceId);

    AgentWorkspaceMemoryItemResponse createMemory(Long userId, Long workspaceId, CreateAgentWorkspaceMemoryRequest request);

    AgentWorkspaceMemoryItemResponse updateMemory(Long userId, Long workspaceId, Long memoryId, UpdateAgentWorkspaceMemoryRequest request);

    void deleteMemory(Long userId, Long workspaceId, Long memoryId);

    PageResponse<InternalWorkspaceMemoryItemResponse> retrieveMemory(Long workspaceId, InternalWorkspaceMemoryRetrieveRequest request);

    record WorkspaceResponse(
            Long id,
            String name,
            String workspaceType,
            String role,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
