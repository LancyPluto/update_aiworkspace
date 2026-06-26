package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.ActivateAgentBranchRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSessionResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentSessionRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentSession;
import com.aiminilab.aitoolmarket.agent.mapper.AgentMessageMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSessionMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentSessionService;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionServiceImpl implements AgentSessionService {

    private static final String DEFAULT_TITLE = "新对话";

    private final AgentSessionMapper agentSessionMapper;
    private final AgentMessageMapper agentMessageMapper;
    private final AgentWorkspaceService agentWorkspaceService;

    public AgentSessionServiceImpl(AgentSessionMapper agentSessionMapper,
                                   AgentMessageMapper agentMessageMapper,
                                   AgentWorkspaceService agentWorkspaceService) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentMessageMapper = agentMessageMapper;
        this.agentWorkspaceService = agentWorkspaceService;
    }

    @Override
    public AgentSessionResponse create(Long userId, CreateAgentSessionRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setWorkspaceId(agentWorkspaceService.resolveWorkspaceId(userId, request == null ? null : request.workspaceId()));
        session.setTitle(normalizeTitle(request == null ? null : request.title()));
        session.setStatus("ACTIVE");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        agentSessionMapper.insertSession(session);
        return AgentSessionResponse.from(session);
    }

    @Override
    public PageResponse<AgentSessionResponse> list(Long userId, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        var sessions = agentSessionMapper.findByUserId(userId, normalizedPageSize, offset)
                .stream()
                .map(AgentSessionResponse::from)
                .toList();
        long total = agentSessionMapper.countByUserId(userId);
        return PageResponse.of(sessions, total, pageNo, pageSize);
    }

    @Override
    public AgentSessionResponse detail(Long userId, Long sessionId) {
        return AgentSessionResponse.from(findSession(userId, sessionId));
    }

    @Override
    public PageResponse<AgentMessageResponse> messages(Long userId, Long sessionId, Integer pageNo, Integer pageSize) {
        AgentSession session = findSession(userId, sessionId);
        return activePathResponse(session, pageNo, pageSize);
    }

    @Override
    @Transactional
    public PageResponse<AgentMessageResponse> activateBranch(Long userId, Long sessionId, ActivateAgentBranchRequest request) {
        AgentSession session = findSession(userId, sessionId);
        var anchor = agentMessageMapper.findByIdSessionAndUser(request.anchorMessageId(), sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "锚点消息不存在"));
        var variant = agentMessageMapper.findByIdSessionAndUser(request.variantMessageId(), sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "分支消息不存在"));
        if (!"ACTIVE".equals(anchor.getStatus()) || !"ACTIVE".equals(variant.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "分支消息不存在或已失效");
        }
        if (!anchor.getRole().equals(variant.getRole()) || !sameMessageId(anchor.getParentMessageId(), variant.getParentMessageId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分支消息与锚点不属于同一组");
        }
        var leaf = agentMessageMapper.findLatestActiveLeafInSubtree(sessionId, variant.getId());
        if (leaf == null) {
            throw new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "分支叶子不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        agentSessionMapper.updateActiveLeaf(sessionId, leaf.getId(), now);
        session.setActiveLeafMessageId(leaf.getId());
        session.setUpdatedAt(now);
        return activePathResponse(session, 1, 100);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long sessionId) {
        findSession(userId, sessionId);
        LocalDateTime now = LocalDateTime.now();
        agentSessionMapper.deleteByIdAndUserId(sessionId, userId, now);
    }

    private AgentSession findSession(Long userId, Long sessionId) {
        return agentSessionMapper.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不存在"));
    }

    private PageResponse<AgentMessageResponse> activePathResponse(AgentSession session, Integer pageNo, Integer pageSize) {
        Long leafId = session.getActiveLeafMessageId();
        if (leafId == null) {
            var latest = agentMessageMapper.findLatestActiveBySession(session.getId());
            leafId = latest == null ? null : latest.getId();
            if (leafId != null) {
                agentSessionMapper.updateActiveLeaf(session.getId(), leafId, LocalDateTime.now());
            }
        }
        List<AgentMessageResponse> messages = leafId == null
                ? List.of()
                : agentMessageMapper.findActivePathByLeaf(session.getId(), leafId)
                        .stream()
                        .map(message -> AgentMessageResponse.from(
                                message,
                                agentMessageMapper.findActiveSiblings(session.getId(), message.getParentMessageId(), message.getRole())
                        ))
                        .toList();
        long totalActive = agentMessageMapper.countBySession(session.getUserId(), session.getId());
        if (leafId != null && messages.size() <= 1 && totalActive > messages.size()) {
            int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
            int offset = PageResponse.offset(pageNo, pageSize);
            var legacyMessages = agentMessageMapper.findBySession(session.getUserId(), session.getId(), normalizedPageSize, offset)
                    .stream()
                    .map(AgentMessageResponse::from)
                    .toList();
            return PageResponse.of(legacyMessages, totalActive, pageNo, pageSize);
        }
        return PageResponse.of(messages, messages.size(), pageNo, pageSize);
    }

    private boolean sameMessageId(Long left, Long right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        String trimmed = title.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
