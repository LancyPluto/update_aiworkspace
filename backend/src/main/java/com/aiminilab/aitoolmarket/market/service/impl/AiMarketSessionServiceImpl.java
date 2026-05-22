package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.market.dto.CreateSessionRequest;
import com.aiminilab.aitoolmarket.market.dto.SessionResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketSession;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketMessageAttachmentMapper;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketMessageMapper;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketSessionMapper;
import com.aiminilab.aitoolmarket.market.service.AiMarketSessionService;
import com.aiminilab.aitoolmarket.market.service.AiMarketToolService;
import com.aiminilab.aitoolmarket.market.support.MarketIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AiMarketSessionServiceImpl implements AiMarketSessionService {

    private final AiMarketSessionMapper aiMarketSessionMapper;
    private final AiMarketMessageMapper aiMarketMessageMapper;
    private final AiMarketMessageAttachmentMapper aiMarketMessageAttachmentMapper;
    private final AiMarketToolService aiMarketToolService;

    public AiMarketSessionServiceImpl(
            AiMarketSessionMapper aiMarketSessionMapper,
            AiMarketMessageMapper aiMarketMessageMapper,
            AiMarketMessageAttachmentMapper aiMarketMessageAttachmentMapper,
            AiMarketToolService aiMarketToolService
    ) {
        this.aiMarketSessionMapper = aiMarketSessionMapper;
        this.aiMarketMessageMapper = aiMarketMessageMapper;
        this.aiMarketMessageAttachmentMapper = aiMarketMessageAttachmentMapper;
        this.aiMarketToolService = aiMarketToolService;
    }

    @Override
    public List<SessionResponse> list(Long userId, String toolId) {
        aiMarketToolService.requireTool(toolId);
        return aiMarketSessionMapper.findByUserAndTool(userId, toolId).stream()
                .map(SessionResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public SessionResponse create(Long userId, CreateSessionRequest request) {
        aiMarketToolService.requireEnabledToolForNewSession(request.toolId());
        LocalDateTime now = LocalDateTime.now();
        AiMarketSession session = new AiMarketSession();
        session.setSessionId(MarketIdGenerator.sessionId());
        session.setUserId(userId);
        session.setToolId(request.toolId());
        session.setTitle("新对话");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setDeleted(false);
        aiMarketSessionMapper.insertSession(session);
        return SessionResponse.from(session);
    }

    @Override
    @Transactional
    public void delete(Long userId, String sessionId) {
        AiMarketSession session = aiMarketSessionMapper.findOptional(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在"));
        LocalDateTime now = LocalDateTime.now();
        if (aiMarketSessionMapper.softDelete(sessionId, userId, now) == 0) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        }
        for (var message : aiMarketMessageMapper.findBySessionId(session.getSessionId())) {
            aiMarketMessageAttachmentMapper.deleteByMessageId(message.getMessageId());
        }
        aiMarketMessageMapper.deleteBySessionId(sessionId);
    }

    @Override
    public AiMarketSession requireOwnedSession(Long userId, String sessionId, String toolId) {
        AiMarketSession session = aiMarketSessionMapper.findOptional(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在"));
        if (!session.getToolId().equals(toolId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话与工具不匹配");
        }
        return session;
    }
}
