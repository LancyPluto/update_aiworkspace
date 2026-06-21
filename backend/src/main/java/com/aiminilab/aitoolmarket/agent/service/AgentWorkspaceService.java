package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentMemoryQuery;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentMemoryUpdateRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentSessionSearchItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentSessionSearchRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalCreateWorkspaceMemoryCandidateRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

import java.time.LocalDateTime;

public interface AgentWorkspaceService {
    PageResponse<WorkspaceResponse> list(Long userId);

    Long resolveWorkspaceId(Long userId, Long requestedWorkspaceId);

    PageResponse<AgentWorkspaceMemoryItemResponse> listMemory(Long userId, Long workspaceId, String status);

    AgentWorkspaceMemoryItemResponse approveMemoryCandidate(Long userId, Long workspaceId, Long memoryId);

    AgentWorkspaceMemoryItemResponse rejectMemoryCandidate(Long userId, Long workspaceId, Long memoryId);

    AgentWorkspaceMemoryItemResponse createMemory(Long userId, Long workspaceId, CreateAgentWorkspaceMemoryRequest request);

    AgentWorkspaceMemoryItemResponse updateMemory(Long userId, Long workspaceId, Long memoryId, UpdateAgentWorkspaceMemoryRequest request);

    void deleteMemory(Long userId, Long workspaceId, Long memoryId);

    AgentWorkspaceMemoryItemResponse updateMemoryPinned(Long userId, Long workspaceId, Long memoryId, boolean pinned);

    AgentWorkspaceMemoryItemResponse createMemoryCandidate(Long workspaceId, InternalCreateWorkspaceMemoryCandidateRequest request);

    PageResponse<InternalWorkspaceMemoryItemResponse> retrieveMemory(Long workspaceId, InternalWorkspaceMemoryRetrieveRequest request);

    PageResponse<InternalAgentSessionSearchItemResponse> searchSession(InternalAgentSessionSearchRequest request);

    PageResponse<AgentWorkspaceMemoryItemResponse> adminListMemory(AdminAgentMemoryQuery query);

    AgentWorkspaceMemoryItemResponse adminUpdateMemory(Long memoryId, AdminAgentMemoryUpdateRequest request);

    AgentWorkspaceMemoryItemResponse adminApproveMemory(Long memoryId);

    AgentWorkspaceMemoryItemResponse adminRejectMemory(Long memoryId);

    void adminDeleteMemory(Long memoryId);

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
