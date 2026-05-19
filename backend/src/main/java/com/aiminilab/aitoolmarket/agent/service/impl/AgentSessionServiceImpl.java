package com.aiminilab.aitoolmarket.agent.service.impl;

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
        findSession(userId, sessionId);
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        var messages = agentMessageMapper.findBySession(userId, sessionId, normalizedPageSize, offset)
                .stream()
                .map(AgentMessageResponse::from)
                .toList();
        long total = agentMessageMapper.countBySession(userId, sessionId);
        return PageResponse.of(messages, total, pageNo, pageSize);
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

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        String trimmed = title.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
