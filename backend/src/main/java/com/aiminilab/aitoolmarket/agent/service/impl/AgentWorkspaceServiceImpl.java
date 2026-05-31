package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentSessionSearchItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentSessionSearchRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalCreateWorkspaceMemoryCandidateRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspace;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMember;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMemoryItem;
import com.aiminilab.aitoolmarket.agent.mapper.AgentMessageMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class AgentWorkspaceServiceImpl implements AgentWorkspaceService {

    private static final String DEFAULT_WORKSPACE_NAME = "Default Workspace";
    private static final String PERSONAL_WORKSPACE_TYPE = "PERSONAL";
    private static final String OWNER_ROLE = "OWNER";
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AgentWorkspaceMapper agentWorkspaceMapper;
    private final AgentWorkspaceMemberMapper agentWorkspaceMemberMapper;
    private final AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper;
    private final AgentMessageMapper agentMessageMapper;
    private final AgentToolCallMapper agentToolCallMapper;

    public AgentWorkspaceServiceImpl(AgentWorkspaceMapper agentWorkspaceMapper,
                                     AgentWorkspaceMemberMapper agentWorkspaceMemberMapper,
                                     AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper,
                                     AgentMessageMapper agentMessageMapper,
                                     AgentToolCallMapper agentToolCallMapper) {
        this.agentWorkspaceMapper = agentWorkspaceMapper;
        this.agentWorkspaceMemberMapper = agentWorkspaceMemberMapper;
        this.agentWorkspaceMemoryItemMapper = agentWorkspaceMemoryItemMapper;
        this.agentMessageMapper = agentMessageMapper;
        this.agentToolCallMapper = agentToolCallMapper;
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
        item.setSourceMessageId(request.sourceMessageId());
        item.setSourceToolCallId(request.sourceToolCallId());
        item.setImportance(clampImportance(request.importance()));
        item.setConfidence(clampConfidence(request.confidence()));
        item.setPinned(Boolean.TRUE.equals(request.pinned()));
        item.setTagsJson(request.tagsJson());
        item.setMetadataJson(request.metadataJson());
        item.setExpiresAt(request.expiresAt());
        item.setAccessCount(0);
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
        existing.setImportance(request.importance() == null ? existing.getImportance() : clampImportance(request.importance()));
        existing.setConfidence(request.confidence() == null ? existing.getConfidence() : clampConfidence(request.confidence()));
        existing.setPinned(request.pinned() == null ? Boolean.TRUE.equals(existing.getPinned()) : Boolean.TRUE.equals(request.pinned()));
        existing.setTagsJson(request.tagsJson() == null ? existing.getTagsJson() : request.tagsJson());
        existing.setMetadataJson(request.metadataJson() == null ? existing.getMetadataJson() : request.metadataJson());
        existing.setExpiresAt(request.expiresAt() == null ? existing.getExpiresAt() : request.expiresAt());
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
    @Transactional
    public AgentWorkspaceMemoryItemResponse updateMemoryPinned(Long userId, Long workspaceId, Long memoryId, boolean pinned) {
        assertWorkspaceMember(workspaceId, userId);
        int affected = agentWorkspaceMemoryItemMapper.updatePinned(workspaceId, memoryId, pinned);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workspace memory item not found");
        }
        return toMemoryResponse(agentWorkspaceMemoryItemMapper.findActiveById(workspaceId, memoryId));
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse createMemoryCandidate(Long workspaceId, InternalCreateWorkspaceMemoryCandidateRequest request) {
        if (request == null || request.userId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "userId is required");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentWorkspaceMemoryItem item = new AgentWorkspaceMemoryItem();
        item.setWorkspaceId(workspaceId);
        item.setUserId(request.userId());
        item.setMemoryType(normalizeMemoryType(request.memoryType()));
        item.setTitle(truncate(defaultString(request.title(), "Memory candidate"), 160));
        item.setContent(defaultString(request.content(), ""));
        item.setSourceRunId(request.sourceRunId());
        item.setSourceMessageId(request.sourceMessageId());
        item.setSourceToolCallId(request.sourceToolCallId());
        item.setImportance(clampImportance(request.importance()));
        item.setConfidence(clampConfidence(request.confidence()));
        item.setPinned(false);
        item.setTagsJson(request.tagsJson());
        item.setMetadataJson(mergeCandidateMetadata(request.reason(), request.metadataJson()));
        item.setExpiresAt(request.expiresAt());
        item.setAccessCount(0);
        item.setStatus("CANDIDATE");
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        agentWorkspaceMemoryItemMapper.insertMemory(item);
        return toMemoryResponse(item);
    }

    @Override
    public PageResponse<InternalWorkspaceMemoryItemResponse> retrieveMemory(Long workspaceId, InternalWorkspaceMemoryRetrieveRequest request) {
        String query = request == null || request.query() == null ? "" : request.query().trim();
        int limit = request == null || request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), 20));

        if (query.isBlank()) {
            var items = agentWorkspaceMemoryItemMapper.findLatestByWorkspace(workspaceId, limit);
            markMemoryAccessed(workspaceId, items);
            return new PageResponse<>(items, items.size(), 1, limit, items.size() == limit);
        }

        var items = agentWorkspaceMemoryItemMapper.findActiveByWorkspaceId(workspaceId);
        if (items.isEmpty()) {
            return new PageResponse<>(Collections.emptyList(), 0, 1, limit, false);
        }

        // 先尝试 FULLTEXT 搜索
        try {
            var ftItems = agentWorkspaceMemoryItemMapper.searchByFulltext(workspaceId, query, limit);
            if (!ftItems.isEmpty()) {
                markMemoryAccessed(workspaceId, ftItems);
                return new PageResponse<>(ftItems, ftItems.size(), 1, limit, ftItems.size() == limit);
            }
        } catch (Exception e) {
            // FULLTEXT 搜索失败（如查询词为停用词导致语法错误），降级为子串匹配
        }

        var fallback = items.stream()
                .map(item -> new ScoredMemoryItem(item, score(item, query.toLowerCase(Locale.ROOT))))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(ScoredMemoryItem::score).reversed()
                        .thenComparing(item -> item.item().getUpdatedAt(), Comparator.reverseOrder())
                        .thenComparing(item -> item.item().getId(), Comparator.reverseOrder()))
                .limit(limit)
                .map(item -> toInternalMemoryResponse(item.item(), item.score()))
                .toList();
        if (!fallback.isEmpty()) {
            markMemoryAccessed(workspaceId, fallback);
            return new PageResponse<>(fallback, fallback.size(), 1, limit, fallback.size() == limit);
        }

        var contextPack = buildMemoryContextPack(items, limit);
        markMemoryAccessed(workspaceId, contextPack);
        return new PageResponse<>(contextPack, contextPack.size(), 1, limit, contextPack.size() == limit);
    }

    @Override
    public PageResponse<InternalAgentSessionSearchItemResponse> searchSession(InternalAgentSessionSearchRequest request) {
        if (request == null || request.userId() == null || request.sessionId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "userId and sessionId are required");
        }
        int limit = request.limit() == null ? 8 : Math.max(1, Math.min(request.limit(), 20));
        String query = request.query() == null ? "" : request.query().trim();
        String toolCode = request.toolCode() == null ? "" : request.toolCode().trim();
        List<InternalAgentSessionSearchItemResponse> results = new ArrayList<>();
        for (AgentToolCall call : agentToolCallMapper.searchBySession(request.userId(), request.sessionId(), query, toolCode, limit)) {
            results.add(toSessionSearchItem(call, query));
        }
        if (results.size() < limit && toolCode.isBlank()) {
            for (AgentMessage message : agentMessageMapper.searchActiveBySession(request.userId(), request.sessionId(), query, limit - results.size())) {
                results.add(toSessionSearchItem(message, query));
            }
        }
        results.sort(Comparator.comparing(InternalAgentSessionSearchItemResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (results.size() > limit) {
            results = results.subList(0, limit);
        }
        return new PageResponse<>(results, results.size(), 1, limit, results.size() == limit);
    }

    private List<InternalWorkspaceMemoryItemResponse> buildMemoryContextPack(List<AgentWorkspaceMemoryItem> items, int limit) {
        var contextPack = items.stream()
                .filter(this::isContextPackMemory)
                .limit(limit)
                .map(item -> toInternalMemoryResponse(item, 0, "context_pack", List.of()))
                .toList();
        if (!contextPack.isEmpty()) {
            return contextPack;
        }
        return items.stream()
                .limit(limit)
                .map(item -> toInternalMemoryResponse(item, 0, "latest", List.of()))
                .toList();
    }

    private boolean isContextPackMemory(AgentWorkspaceMemoryItem item) {
        if (Boolean.TRUE.equals(item.getPinned())) {
            return true;
        }
        Integer importance = item.getImportance();
        if (importance != null && importance >= 7) {
            return true;
        }
        String type = normalizeMemoryType(item.getMemoryType());
        return switch (type) {
            case "user_profile", "preference", "workflow_recipe" -> true;
            default -> false;
        };
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
                item.getSourceMessageId(),
                item.getSourceToolCallId(),
                item.getImportance(),
                item.getConfidence(),
                Boolean.TRUE.equals(item.getPinned()),
                item.getTagsJson(),
                item.getMetadataJson(),
                item.getLastAccessedAt(),
                item.getAccessCount(),
                item.getExpiresAt(),
                item.getStatus(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    private InternalWorkspaceMemoryItemResponse toInternalMemoryResponse(AgentWorkspaceMemoryItem item, int score) {
        return toInternalMemoryResponse(
                item,
                score,
                score > 0 ? "substring" : "latest",
                matchedFields(item, score)
        );
    }

    private InternalWorkspaceMemoryItemResponse toInternalMemoryResponse(
            AgentWorkspaceMemoryItem item,
            int score,
            String reason,
            List<String> matchedFields
    ) {
        return new InternalWorkspaceMemoryItemResponse(
                item.getId(),
                item.getWorkspaceId(),
                item.getUserId(),
                item.getSourceRunId(),
                item.getSourceMessageId(),
                item.getSourceToolCallId(),
                item.getTitle(),
                item.getContent(),
                item.getMemoryType(),
                item.getStatus(),
                score,
                reason,
                matchedFields,
                item.getImportance(),
                item.getConfidence(),
                Boolean.TRUE.equals(item.getPinned()),
                item.getTagsJson(),
                item.getMetadataJson(),
                item.getLastAccessedAt(),
                item.getAccessCount(),
                item.getExpiresAt(),
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

    private void markMemoryAccessed(Long workspaceId, List<InternalWorkspaceMemoryItemResponse> items) {
        String ids = items.stream()
                .map(InternalWorkspaceMemoryItemResponse::id)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> value.matches("\\d+"))
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (!ids.isBlank()) {
            agentWorkspaceMemoryItemMapper.markAccessed(workspaceId, ids);
        }
    }

    private List<String> matchedFields(AgentWorkspaceMemoryItem item, int score) {
        if (score <= 0) {
            return List.of();
        }
        if (score >= 2) {
            return List.of("title");
        }
        return List.of("content");
    }

    private InternalAgentSessionSearchItemResponse toSessionSearchItem(AgentMessage message, String query) {
        return new InternalAgentSessionSearchItemResponse(
                "message",
                message.getId(),
                message.getRunId(),
                null,
                message.getRole(),
                null,
                truncate(message.getContentText(), 1200),
                null,
                null,
                null,
                null,
                textScore(message.getContentText(), query),
                message.getCreatedAt()
        );
    }

    private InternalAgentSessionSearchItemResponse toSessionSearchItem(AgentToolCall call, String query) {
        String combined = defaultString(call.getToolCode(), "") + "\n"
                + defaultString(call.getArgumentsJson(), "") + "\n"
                + defaultString(call.getResultJson(), "");
        return new InternalAgentSessionSearchItemResponse(
                "tool_call",
                call.getId(),
                call.getRunId(),
                call.getTaskId(),
                null,
                call.getToolCode(),
                truncate(combined, 1200),
                truncate(call.getArgumentsJson(), 1200),
                truncate(call.getResultJson(), 1200),
                call.getErrorCode(),
                truncate(call.getErrorMessage(), 800),
                textScore(combined, query),
                call.getCreatedAt()
        );
    }

    private int textScore(String text, String query) {
        if (query == null || query.isBlank()) {
            return 1;
        }
        String lowerText = defaultString(text, "").toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        return lowerText.contains(lowerQuery) ? 2 : 0;
    }

    private String normalizeMemoryType(String memoryType) {
        String value = defaultString(memoryType, "custom").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "user_profile", "workspace_fact", "preference", "tool_lesson", "workflow_recipe", "custom" -> value;
            case "project", "project_knowledge" -> "workspace_fact";
            case "profile" -> "user_profile";
            default -> "custom";
        };
    }

    private Integer clampImportance(Integer value) {
        if (value == null) {
            return 5;
        }
        return Math.max(0, Math.min(value, 10));
    }

    private Double clampConfidence(Double value) {
        if (value == null) {
            return 0.7d;
        }
        return Math.max(0d, Math.min(value, 1d));
    }

    private String mergeCandidateMetadata(String reason, String metadataJson) {
        if (reason == null || reason.isBlank()) {
            return metadataJson;
        }
        if (metadataJson == null || metadataJson.isBlank()) {
            return "{\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}";
        }
        return metadataJson;
    }

    private String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record ScoredMemoryItem(AgentWorkspaceMemoryItem item, int score) {
    }
}
