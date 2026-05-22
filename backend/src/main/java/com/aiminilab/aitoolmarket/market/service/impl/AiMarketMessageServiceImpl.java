package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.market.dto.MessageAttachmentResponse;
import com.aiminilab.aitoolmarket.market.dto.MessageResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketMessage;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketMessageAttachmentMapper;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketMessageMapper;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketSessionMapper;
import com.aiminilab.aitoolmarket.market.service.AiMarketMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiMarketMessageServiceImpl implements AiMarketMessageService {

    private final AiMarketSessionMapper aiMarketSessionMapper;
    private final AiMarketMessageMapper aiMarketMessageMapper;
    private final AiMarketMessageAttachmentMapper aiMarketMessageAttachmentMapper;
    private final ObjectMapper objectMapper;

    public AiMarketMessageServiceImpl(
            AiMarketSessionMapper aiMarketSessionMapper,
            AiMarketMessageMapper aiMarketMessageMapper,
            AiMarketMessageAttachmentMapper aiMarketMessageAttachmentMapper,
            ObjectMapper objectMapper
    ) {
        this.aiMarketSessionMapper = aiMarketSessionMapper;
        this.aiMarketMessageMapper = aiMarketMessageMapper;
        this.aiMarketMessageAttachmentMapper = aiMarketMessageAttachmentMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MessageResponse> list(Long userId, String sessionId) {
        if (aiMarketSessionMapper.findOptional(sessionId, userId).isEmpty()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        }
        return aiMarketMessageMapper.findBySessionId(sessionId).stream()
                .map(this::toResponse)
                .toList();
    }

    private MessageResponse toResponse(AiMarketMessage message) {
        List<MessageAttachmentResponse> attachments = aiMarketMessageAttachmentMapper.findByMessageId(message.getMessageId())
                .stream()
                .map(MessageAttachmentResponse::from)
                .toList();
        return MessageResponse.from(message, attachments, objectMapper);
    }
}
