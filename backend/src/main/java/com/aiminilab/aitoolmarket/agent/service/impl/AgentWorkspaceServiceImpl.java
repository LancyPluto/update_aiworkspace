package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspace;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMember;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMemoryItem;
import com.aiminilab.aitoolmarket.agent.mapper.AgentWorkspaceMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentWorkspaceMemberMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentWorkspaceMemoryItemMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class AgentWorkspaceServiceImpl implements AgentWorkspaceService {

    private static final String DEFAULT_WORKSPACE_NAME = "Default Workspace";
    private static final String PERSONAL_WORKSPACE_TYPE = "PERSONAL";
    private static final String OWNER_ROLE = "OWNER";
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AgentWorkspaceMapper agentWorkspaceMapper;
    private final AgentWorkspaceMemberMapper agentWorkspaceMemberMapper;
    private final AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper;

    public AgentWorkspaceServiceImpl(AgentWorkspaceMapper agentWorkspaceMapper,
                                     AgentWorkspaceMemberMapper agentWorkspaceMemberMapper,
                                     AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper) {
        this.agentWorkspaceMapper = agentWorkspaceMapper;
        this.agentWorkspaceMemberMapper = agentWorkspaceMemberMapper;
        this.agentWorkspaceMemoryItemMapper = agentWorkspaceMemoryItemMapper;
    }

    @Override
    @Transactional
    public PageResponse<WorkspaceResponse> list(Long userId) {
        ensureDefaultWorkspace(userId);
        var workspaces = agentWorkspaceMapper.findActiveByMemberUserId(userId)
                .stream()
                .map(row -> new WorkspaceResponse(
                        row.getId(),
                        row.getName(),
                        row.getWorkspaceType(),
                        row.getRole(),
                        row.getStatus(),
                        row.getCreatedAt(),
                        row.getUpdatedAt()
                ))
                .toList();
        return new PageResponse<>(workspaces, workspaces.size());
    }

    @Override
    @Transactional
    public Long resolveWorkspaceId(Long userId, Long requestedWorkspaceId) {
        if (requestedWorkspaceId != null) {
            assertWorkspaceMember(requestedWorkspaceId, userId);
            return requestedWorkspaceId;
        }
        return ensureDefaultWorkspace(userId).getId();
    }

    @Override
    public PageResponse<AgentWorkspaceMemoryItemResponse> listMemory(Long userId, Long workspaceId) {
        assertWorkspaceMember(workspaceId, userId);
        var items = agentWorkspaceMemoryItemMapper.findActiveByWorkspaceId(workspaceId)
                .stream()
                .map(this::toMemoryResponse)
                .toList();
        return new PageResponse<>(items, items.size());
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse createMemory(Long userId, Long workspaceId, CreateAgentWorkspaceMemoryRequest request) {
        assertWorkspaceMember(workspaceId, userId);
        LocalDateTime now = LocalDateTime.now();
        AgentWorkspaceMemoryItem item = new AgentWorkspaceMemoryItem();
        item.setWorkspaceId(workspaceId);
        item.setUserId(userId);
        item.setMemoryType(request.memoryType());
        item.setTitle(request.title());
        item.setContent(request.content());
        item.setSourceRunId(request.sourceRunId());
        item.setStatus(ACTIVE_STATUS);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        agentWorkspaceMemoryItemMapper.insertMemory(item);
        return toMemoryResponse(item);
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse updateMemory(Long userId, Long workspaceId, Long memoryId, UpdateAgentWorkspaceMemoryRequest request) {
        assertWorkspaceMember(workspaceId, userId);
        AgentWorkspaceMemoryItem existing = agentWorkspaceMemoryItemMapper.findActiveById(workspaceId, memoryId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workspace memory item not found");
        }
        existing.setMemoryType(request.memoryType());
        existing.setTitle(request.title());
        existing.setContent(request.content());
        existing.setUpdatedAt(LocalDateTime.now());
        agentWorkspaceMemoryItemMapper.updateMemory(existing);
        return toMemoryResponse(existing);
    }

    @Override
    @Transactional
    public void deleteMemory(Long userId, Long workspaceId, Long memoryId) {
        assertWorkspaceMember(workspaceId, userId);
        int affected = agentWorkspaceMemoryItemMapper.softDelete(workspaceId, memoryId);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workspace memory item not found");
        }
    }

    @Override
    public PageResponse<InternalWorkspaceMemoryItemResponse> retrieveMemory(Long workspaceId, InternalWorkspaceMemoryRetrieveRequest request) {
        String query = request == null || request.query() == null ? "" : request.query().trim();
        int limit = request == null || request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), 20));

        if (query.isBlank()) {
            var items = agentWorkspaceMemoryItemMapper.findLatestByWorkspace(workspaceId, limit);
            return new PageResponse<>(items, items.size(), 1, limit, items.size() == limit);
        }

        var items = agentWorkspaceMemoryItemMapper.findActiveByWorkspaceId(workspaceId);
        if (items.isEmpty()) {
            return new PageResponse<>(Collections.emptyList(), 0, 1, limit, false);
        }

        // 先尝试 FULLTEXT 搜索
        try {
            var ftItems = agentWorkspaceMemoryItemMapper.searchByFulltext(workspaceId, query, limit);
            return new PageResponse<>(ftItems, ftItems.size(), 1, limit, ftItems.size() == limit);
        } catch (Exception e) {
            // FULLTEXT 搜索失败（如查询词为停用词导致语法错误），降级为子串匹配
            var fallback = items.stream()
                    .map(item -> new ScoredMemoryItem(item, score(item, query.toLowerCase())))
                    .filter(item -> item.score() > 0)
                    .sorted(Comparator.comparingInt(ScoredMemoryItem::score).reversed()
                            .thenComparing(item -> item.item().getUpdatedAt(), Comparator.reverseOrder())
                            .thenComparing(item -> item.item().getId(), Comparator.reverseOrder()))
                    .limit(limit)
                    .map(item -> toInternalMemoryResponse(item.item(), item.score()))
                    .toList();
            return new PageResponse<>(fallback, fallback.size(), 1, limit, fallback.size() == limit);
        }
    }

    private AgentWorkspace ensureDefaultWorkspace(Long userId) {
        AgentWorkspace existing = agentWorkspaceMapper.findPersonalByOwnerUserId(userId);
        if (existing != null) {
            ensureOwnerMember(existing.getId(), userId, existing.getCreatedAt());
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        AgentWorkspace workspace = new AgentWorkspace();
        workspace.setOwnerUserId(userId);
        workspace.setName(DEFAULT_WORKSPACE_NAME);
        workspace.setWorkspaceType(PERSONAL_WORKSPACE_TYPE);
        workspace.setStatus(ACTIVE_STATUS);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        agentWorkspaceMapper.insertWorkspace(workspace);
        ensureOwnerMember(workspace.getId(), userId, now);
        return workspace;
    }

    private void ensureOwnerMember(Long workspaceId, Long userId, LocalDateTime timestamp) {
        if (agentWorkspaceMemberMapper.findActiveMember(workspaceId, userId) != null) {
            return;
        }
        LocalDateTime now = timestamp == null ? LocalDateTime.now() : timestamp;
        AgentWorkspaceMember member = new AgentWorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(OWNER_ROLE);
        member.setStatus(ACTIVE_STATUS);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        agentWorkspaceMemberMapper.insertMember(member);
    }

    private void assertWorkspaceMember(Long workspaceId, Long userId) {
        if (agentWorkspaceMemberMapper.findActiveMember(workspaceId, userId) == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Workspace access denied");
        }
    }

    private AgentWorkspaceMemoryItemResponse toMemoryResponse(AgentWorkspaceMemoryItem item) {
        return new AgentWorkspaceMemoryItemResponse(
                item.getId(),
                item.getWorkspaceId(),
                item.getUserId(),
                item.getMemoryType(),
                item.getTitle(),
                item.getContent(),
                item.getSourceRunId(),
                item.getStatus(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    private InternalWorkspaceMemoryItemResponse toInternalMemoryResponse(AgentWorkspaceMemoryItem item, int score) {
        return new InternalWorkspaceMemoryItemResponse(
                item.getId(),
                item.getWorkspaceId(),
                item.getSourceRunId(),
                item.getTitle(),
                item.getContent(),
                item.getMemoryType(),
                item.getStatus(),
                score,
                item.getUpdatedAt()
        );
    }

    private int score(AgentWorkspaceMemoryItem item, String query) {
        if (query.isBlank()) {
            return 1;
        }
        String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase();
        if (title.contains(query)) {
            return 2;
        }
        String content = item.getContent() == null ? "" : item.getContent().toLowerCase();
        if (content.contains(query)) {
            return 1;
        }
        return 0;
    }

    private record ScoredMemoryItem(AgentWorkspaceMemoryItem item, int score) {
    }
}
