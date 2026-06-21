package com.aiminilab.aitoolmarket.agent.service.impl;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentWorkspaceServiceImpl implements AgentWorkspaceService {

    private static final String DEFAULT_WORKSPACE_NAME = "Default Workspace";
    private static final String PERSONAL_WORKSPACE_TYPE = "PERSONAL";
    private static final String OWNER_ROLE = "OWNER";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final int MIN_RELEVANCE_SCORE = 2;
    private static final Set<String> SAFE_TOOL_MEMORY_TYPES = Set.of("user_profile", "preference");
    private static final Set<String> CHAT_CONTEXT_PACK_TYPES = Set.of("user_profile", "preference", "workflow_recipe");

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
    public PageResponse<AgentWorkspaceMemoryItemResponse> listMemory(Long userId, Long workspaceId, String status) {
        assertWorkspaceMember(workspaceId, userId);
        String normalizedStatus = normalizeUserMemoryStatus(status);
        var items = ("CANDIDATE".equals(normalizedStatus)
                ? agentWorkspaceMemoryItemMapper.findByWorkspaceIdAndStatus(workspaceId, "CANDIDATE")
                : agentWorkspaceMemoryItemMapper.findActiveByWorkspaceId(workspaceId))
                .stream()
                .filter(item -> !isMemoryExpired(item))
                .map(this::toMemoryResponse)
                .toList();
        return new PageResponse<>(items, items.size());
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse approveMemoryCandidate(Long userId, Long workspaceId, Long memoryId) {
        assertWorkspaceMember(workspaceId, userId);
        AgentWorkspaceMemoryItem existing = agentWorkspaceMemoryItemMapper.findAnyById(memoryId);
        if (existing == null || !workspaceId.equals(existing.getWorkspaceId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workspace memory item not found");
        }
        if (!"CANDIDATE".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Only candidate memories can be approved");
        }
        existing.setStatus(ACTIVE_STATUS);
        existing.setUpdatedAt(LocalDateTime.now());
        agentWorkspaceMemoryItemMapper.updateMemory(existing);
        return toMemoryResponse(existing);
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse rejectMemoryCandidate(Long userId, Long workspaceId, Long memoryId) {
        assertWorkspaceMember(workspaceId, userId);
        AgentWorkspaceMemoryItem existing = agentWorkspaceMemoryItemMapper.findAnyById(memoryId);
        if (existing == null || !workspaceId.equals(existing.getWorkspaceId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workspace memory item not found");
        }
        if (!"CANDIDATE".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Only candidate memories can be rejected");
        }
        agentWorkspaceMemoryItemMapper.adminUpdateStatus(memoryId, "REJECTED");
        return toMemoryResponse(agentWorkspaceMemoryItemMapper.findAnyById(memoryId));
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse createMemory(Long userId, Long workspaceId, CreateAgentWorkspaceMemoryRequest request) {
        assertWorkspaceMember(workspaceId, userId);
        LocalDateTime now = LocalDateTime.now();
        AgentWorkspaceMemoryItem item = new AgentWorkspaceMemoryItem();
        item.setWorkspaceId(workspaceId);
        item.setUserId(userId);
        item.setMemoryType(normalizeMemoryType(request.memoryType()));
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
        existing.setMemoryType(normalizeMemoryType(request.memoryType()));
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
        String view = request == null || request.view() == null ? "" : request.view().trim().toLowerCase(Locale.ROOT);
        int limit = request == null || request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), 20));
        List<Long> memoryIds = request == null || request.memoryIds() == null
                ? List.of()
                : request.memoryIds().stream().filter(Objects::nonNull).distinct().toList();
        Long sessionId = request == null ? null : request.sessionId();

        if ("tool_explicit".equals(view) && !memoryIds.isEmpty()) {
            var explicitItems = retrieveExplicitMemories(workspaceId, memoryIds, limit);
            markMemoryAccessed(workspaceId, explicitItems);
            return new PageResponse<>(explicitItems, explicitItems.size(), 1, limit, explicitItems.size() == limit);
        }

        if (query.isBlank() && !"tool".equals(view) && !"tool_explicit".equals(view)) {
            var items = agentWorkspaceMemoryItemMapper.findLatestByWorkspace(workspaceId, limit);
            markMemoryAccessed(workspaceId, items);
            return new PageResponse<>(items, items.size(), 1, limit, items.size() == limit);
        }

        var items = agentWorkspaceMemoryItemMapper.findActiveByWorkspaceId(workspaceId).stream()
                .filter(item -> shouldIncludeMemoryForSession(item, sessionId))
                .filter(item -> !isMemoryExpired(item))
                .toList();
        if (items.isEmpty()) {
            return new PageResponse<>(Collections.emptyList(), 0, 1, limit, false);
        }

        if ("tool".equals(view)) {
            var toolItems = retrieveToolViewMemory(workspaceId, items, query, limit, false);
            markMemoryAccessed(workspaceId, toolItems);
            return new PageResponse<>(toolItems, toolItems.size(), 1, limit, toolItems.size() == limit);
        }
        if ("tool_explicit".equals(view)) {
            var toolItems = retrieveToolViewMemory(workspaceId, items, query, limit, true);
            markMemoryAccessed(workspaceId, toolItems);
            return new PageResponse<>(toolItems, toolItems.size(), 1, limit, toolItems.size() == limit);
        }

        // chat / router: relevance search, then safe context pack fallback
        var chatItems = retrieveChatViewMemory(workspaceId, items, query, limit);
        markMemoryAccessed(workspaceId, chatItems);
        return new PageResponse<>(chatItems, chatItems.size(), 1, limit, chatItems.size() == limit);
    }

    private List<InternalWorkspaceMemoryItemResponse> retrieveExplicitMemories(Long workspaceId, List<Long> memoryIds, int limit) {
        List<InternalWorkspaceMemoryItemResponse> result = new ArrayList<>();
        for (Long memoryId : memoryIds) {
            if (result.size() >= limit) {
                break;
            }
            AgentWorkspaceMemoryItem item = agentWorkspaceMemoryItemMapper.findActiveById(workspaceId, memoryId);
            if (item != null) {
                result.add(toInternalMemoryResponse(item, 0, "explicit", List.of("id")));
            }
        }
        return result;
    }

    private List<InternalWorkspaceMemoryItemResponse> retrieveChatViewMemory(
            Long workspaceId,
            List<AgentWorkspaceMemoryItem> items,
            String query,
            int limit
    ) {
        List<InternalWorkspaceMemoryItemResponse> result = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        if (!query.isBlank()) {
            appendRelevantMemories(result, seenIds, workspaceId, items, query, limit, true, 1);
        }
        if (result.size() < limit) {
            for (AgentWorkspaceMemoryItem item : items) {
                if (result.size() >= limit) {
                    break;
                }
                if (isChatContextPackMemory(item)) {
                    appendUniqueMemory(result, seenIds, toInternalMemoryResponse(item, 0, "context_pack", List.of()), limit);
                }
            }
        }
        if (result.isEmpty()) {
            appendUniqueMemories(result, seenIds, buildSafeContextPack(items, limit), limit);
        }
        return result;
    }

    private List<InternalWorkspaceMemoryItemResponse> retrieveToolViewMemory(
            Long workspaceId,
            List<AgentWorkspaceMemoryItem> items,
            String query,
            int limit,
            boolean explicitProjectFacts
    ) {
        List<InternalWorkspaceMemoryItemResponse> result = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        if (!explicitProjectFacts) {
            for (AgentWorkspaceMemoryItem item : items) {
                if (result.size() >= limit) {
                    break;
                }
                if (isSafeToolContextPackMemory(item)) {
                    appendUniqueMemory(result, seenIds, toInternalMemoryResponse(item, 0, "safe_pack", List.of()), limit);
                }
            }
        }
        if (!query.isBlank()) {
            appendRelevantMemories(result, seenIds, workspaceId, items, query, limit, explicitProjectFacts, MIN_RELEVANCE_SCORE);
        }
        if (result.isEmpty() && explicitProjectFacts) {
            appendUniqueMemories(result, seenIds, buildSafeContextPack(items, limit), limit);
        }
        return result;
    }

    private void appendRelevantMemories(
            List<InternalWorkspaceMemoryItemResponse> result,
            Set<Long> seenIds,
            Long workspaceId,
            List<AgentWorkspaceMemoryItem> items,
            String query,
            int limit,
            boolean includeProjectFacts,
            int minScore
    ) {
        if (result.size() >= limit || query.isBlank()) {
            return;
        }
        String normalizedQuery = normalizeMemoryQuery(query);
        try {
            for (InternalWorkspaceMemoryItemResponse candidate : agentWorkspaceMemoryItemMapper.searchByFulltext(workspaceId, normalizedQuery, limit)) {
                if (result.size() >= limit) {
                    return;
                }
                if (!includeProjectFacts && !isRetrievableForToolView(candidate.memoryType())) {
                    continue;
                }
                if (!includeProjectFacts && isProjectFactType(candidate.memoryType()) && candidate.score() < MIN_RELEVANCE_SCORE) {
                    continue;
                }
                appendUniqueMemory(result, seenIds, candidate, limit);
            }
        } catch (Exception ignored) {
            // FULLTEXT may fail on stop words; fall back to substring scoring.
        }
        if (result.size() >= limit) {
            return;
        }
        var fallback = items.stream()
                .map(item -> new ScoredMemoryItem(item, score(item, normalizedQuery.toLowerCase(Locale.ROOT))))
                .filter(item -> item.score() >= minScore)
                .filter(item -> includeProjectFacts || isRetrievableForToolView(item.item().getMemoryType()))
                .sorted(Comparator.comparingInt(ScoredMemoryItem::score).reversed()
                        .thenComparing(item -> item.item().getUpdatedAt(), Comparator.reverseOrder())
                        .thenComparing(item -> item.item().getId(), Comparator.reverseOrder()))
                .limit(limit)
                .map(item -> toInternalMemoryResponse(item.item(), item.score()))
                .toList();
        appendUniqueMemories(result, seenIds, fallback, limit);
    }

    private static String normalizeMemoryQuery(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() <= 12) {
            StringBuilder keywords = new StringBuilder();
            for (String token : trimmed.split("[\\s，,。！？!?；;：:\\-]+")) {
                String piece = token.trim();
                if (piece.length() >= 2) {
                    if (!keywords.isEmpty()) {
                        keywords.append(' ');
                    }
                    keywords.append(piece);
                }
            }
            if (!keywords.isEmpty()) {
                return keywords.toString();
            }
        }
        return trimmed;
    }

    private void appendUniqueMemories(
            List<InternalWorkspaceMemoryItemResponse> result,
            Set<Long> seenIds,
            List<InternalWorkspaceMemoryItemResponse> candidates,
            int limit
    ) {
        for (InternalWorkspaceMemoryItemResponse candidate : candidates) {
            if (result.size() >= limit) {
                return;
            }
            appendUniqueMemory(result, seenIds, candidate, limit);
        }
    }

    private void appendUniqueMemory(
            List<InternalWorkspaceMemoryItemResponse> result,
            Set<Long> seenIds,
            InternalWorkspaceMemoryItemResponse candidate,
            int limit
    ) {
        if (candidate == null || result.size() >= limit) {
            return;
        }
        Long id = candidate.id();
        if (id != null && !seenIds.add(id)) {
            return;
        }
        result.add(candidate);
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

    @Override
    public PageResponse<AgentWorkspaceMemoryItemResponse> adminListMemory(AdminAgentMemoryQuery query) {
        AdminAgentMemoryQuery q = query == null ? new AdminAgentMemoryQuery(null, null, null, null, null, 1, 20) : query;
        int pageNo = PageResponse.normalizePageNo(q.pageNo());
        int pageSize = PageResponse.normalizePageSize(q.pageSize());
        String status = normalizeAdminStatus(q.status());
        String memoryType = q.memoryType() == null || q.memoryType().isBlank() ? "" : normalizeMemoryType(q.memoryType());
        String keyword = q.keyword() == null ? "" : q.keyword().trim();
        long total = agentWorkspaceMemoryItemMapper.adminCount(q.userId(), q.workspaceId(), status, memoryType, keyword);
        var items = agentWorkspaceMemoryItemMapper.adminSearch(
                        q.userId(),
                        q.workspaceId(),
                        status,
                        memoryType,
                        keyword,
                        pageSize,
                        (pageNo - 1) * pageSize
                )
                .stream()
                .map(this::toMemoryResponse)
                .toList();
        return new PageResponse<>(items, total, pageNo, pageSize, (long) pageNo * pageSize < total);
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse adminUpdateMemory(Long memoryId, AdminAgentMemoryUpdateRequest request) {
        AgentWorkspaceMemoryItem existing = requireAdminMemory(memoryId);
        existing.setMemoryType(normalizeMemoryType(request.memoryType()));
        existing.setTitle(truncate(request.title(), 160));
        existing.setContent(request.content());
        existing.setImportance(request.importance() == null ? existing.getImportance() : clampImportance(request.importance()));
        existing.setConfidence(request.confidence() == null ? existing.getConfidence() : clampConfidence(request.confidence()));
        existing.setPinned(request.pinned() == null ? Boolean.TRUE.equals(existing.getPinned()) : Boolean.TRUE.equals(request.pinned()));
        existing.setTagsJson(request.tagsJson() == null ? existing.getTagsJson() : request.tagsJson());
        existing.setMetadataJson(request.metadataJson() == null ? existing.getMetadataJson() : request.metadataJson());
        existing.setExpiresAt(request.expiresAt() == null ? existing.getExpiresAt() : request.expiresAt());
        existing.setUpdatedAt(LocalDateTime.now());
        agentWorkspaceMemoryItemMapper.adminUpdateMemory(existing);
        return toMemoryResponse(agentWorkspaceMemoryItemMapper.findAnyById(memoryId));
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse adminApproveMemory(Long memoryId) {
        AgentWorkspaceMemoryItem existing = requireAdminMemory(memoryId);
        existing.setStatus(ACTIVE_STATUS);
        existing.setUpdatedAt(LocalDateTime.now());
        agentWorkspaceMemoryItemMapper.adminUpdateMemory(existing);
        return toMemoryResponse(agentWorkspaceMemoryItemMapper.findAnyById(memoryId));
    }

    @Override
    @Transactional
    public AgentWorkspaceMemoryItemResponse adminRejectMemory(Long memoryId) {
        requireAdminMemory(memoryId);
        agentWorkspaceMemoryItemMapper.adminUpdateStatus(memoryId, "REJECTED");
        return toMemoryResponse(agentWorkspaceMemoryItemMapper.findAnyById(memoryId));
    }

    @Override
    @Transactional
    public void adminDeleteMemory(Long memoryId) {
        requireAdminMemory(memoryId);
        agentWorkspaceMemoryItemMapper.adminUpdateStatus(memoryId, "DELETED");
    }

    private List<InternalWorkspaceMemoryItemResponse> buildMemoryContextPack(List<AgentWorkspaceMemoryItem> items, int limit) {
        return buildSafeContextPack(items, limit);
    }

    private List<InternalWorkspaceMemoryItemResponse> buildSafeContextPack(List<AgentWorkspaceMemoryItem> items, int limit) {
        var contextPack = items.stream()
                .filter(this::isChatContextPackMemory)
                .limit(limit)
                .map(item -> toInternalMemoryResponse(item, 0, "context_pack", List.of()))
                .toList();
        if (!contextPack.isEmpty()) {
            return contextPack;
        }
        return items.stream()
                .filter(item -> isRetrievableForToolView(item.getMemoryType()))
                .limit(limit)
                .map(item -> toInternalMemoryResponse(item, 0, "latest", List.of()))
                .toList();
    }

    private boolean isSafeToolContextPackMemory(AgentWorkspaceMemoryItem item) {
        return isRetrievableForToolView(item.getMemoryType());
    }

    private boolean isChatContextPackMemory(AgentWorkspaceMemoryItem item) {
        return isContextPackMemory(item);
    }

    private boolean isContextPackMemory(AgentWorkspaceMemoryItem item) {
        String type = normalizeMemoryType(item.getMemoryType());
        if (CHAT_CONTEXT_PACK_TYPES.contains(type)) {
            return true;
        }
        if (isProjectFactType(type)) {
            return false;
        }
        if (Boolean.TRUE.equals(item.getPinned())) {
            return true;
        }
        Integer importance = item.getImportance();
        return importance != null && importance >= 7;
    }

    private boolean isRetrievableForToolView(String memoryType) {
        return SAFE_TOOL_MEMORY_TYPES.contains(normalizeMemoryType(memoryType));
    }

    private boolean isProjectFactType(String memoryType) {
        return "workspace_fact".equals(normalizeMemoryType(memoryType));
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

    private boolean shouldIncludeMemoryForSession(AgentWorkspaceMemoryItem item, Long sessionId) {
        if (!isSessionScopedMemory(item)) {
            return true;
        }
        if (sessionId == null) {
            return false;
        }
        Long scopedSessionId = readSessionScopeId(item.getMetadataJson());
        return scopedSessionId != null && scopedSessionId.equals(sessionId);
    }

    private boolean isMemoryExpired(AgentWorkspaceMemoryItem item) {
        return item.getExpiresAt() != null && item.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String normalizeUserMemoryStatus(String status) {
        if (status == null || status.isBlank()) {
            return ACTIVE_STATUS;
        }
        return "CANDIDATE".equalsIgnoreCase(status.trim()) ? "CANDIDATE" : ACTIVE_STATUS;
    }

    private boolean isSessionScopedMemory(AgentWorkspaceMemoryItem item) {
        String metadata = item.getMetadataJson();
        return metadata != null && metadata.contains("\"scope\":\"session\"");
    }

    private Long readSessionScopeId(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"sourceSessionId\"\\s*:\\s*(\\d+)").matcher(metadataJson);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
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

    private String normalizeAdminStatus(String status) {
        String value = defaultString(status, "").trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() || "ALL".equals(value)) {
            return "";
        }
        return switch (value) {
            case "ACTIVE", "CANDIDATE", "REJECTED", "DELETED" -> value;
            default -> "";
        };
    }

    private AgentWorkspaceMemoryItem requireAdminMemory(Long memoryId) {
        AgentWorkspaceMemoryItem existing = agentWorkspaceMemoryItemMapper.findAnyById(memoryId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Workspace memory item not found");
        }
        return existing;
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
