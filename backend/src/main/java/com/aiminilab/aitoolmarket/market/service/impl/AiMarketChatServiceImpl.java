package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatCompletionRequest;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatCompletionResponse;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatMessageDto;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.market.dto.ChatMessageRequest;
import com.aiminilab.aitoolmarket.market.dto.ChatMessageResponse;
import com.aiminilab.aitoolmarket.market.dto.MessageAttachmentResponse;
import com.aiminilab.aitoolmarket.market.dto.MessageResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketFile;
import com.aiminilab.aitoolmarket.market.entity.AiMarketMessage;
import com.aiminilab.aitoolmarket.market.entity.AiMarketMessageAttachment;
import com.aiminilab.aitoolmarket.market.entity.AiMarketSession;
import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketMessageAttachmentMapper;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketMessageMapper;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketSessionMapper;
import com.aiminilab.aitoolmarket.market.service.AiMarketChatService;
import com.aiminilab.aitoolmarket.market.service.AiMarketFileService;
import com.aiminilab.aitoolmarket.market.service.AiMarketSessionService;
import com.aiminilab.aitoolmarket.market.service.AiMarketToolService;
import com.aiminilab.aitoolmarket.market.support.MarketIdGenerator;
import com.aiminilab.aitoolmarket.market.support.MarketModelConfigBridge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiMarketChatServiceImpl implements AiMarketChatService {

    private static final String DEFAULT_TITLE = "新对话";

    private final AiMarketSessionService aiMarketSessionService;
    private final AiMarketToolService aiMarketToolService;
    private final AiMarketFileService aiMarketFileService;
    private final AiMarketMessageMapper aiMarketMessageMapper;
    private final AiMarketMessageAttachmentMapper aiMarketMessageAttachmentMapper;
    private final AiMarketSessionMapper aiMarketSessionMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentServiceClient agentServiceClient;
    private final MarketModelConfigBridge marketModelConfigBridge;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public AiMarketChatServiceImpl(
            AiMarketSessionService aiMarketSessionService,
            AiMarketToolService aiMarketToolService,
            AiMarketFileService aiMarketFileService,
            AiMarketMessageMapper aiMarketMessageMapper,
            AiMarketMessageAttachmentMapper aiMarketMessageAttachmentMapper,
            AiMarketSessionMapper aiMarketSessionMapper,
            AgentModelConfigMapper agentModelConfigMapper,
            AgentServiceClient agentServiceClient,
            MarketModelConfigBridge marketModelConfigBridge,
            AppProperties appProperties,
            ObjectMapper objectMapper
    ) {
        this.aiMarketSessionService = aiMarketSessionService;
        this.aiMarketToolService = aiMarketToolService;
        this.aiMarketFileService = aiMarketFileService;
        this.aiMarketMessageMapper = aiMarketMessageMapper;
        this.aiMarketMessageAttachmentMapper = aiMarketMessageAttachmentMapper;
        this.aiMarketSessionMapper = aiMarketSessionMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentServiceClient = agentServiceClient;
        this.marketModelConfigBridge = marketModelConfigBridge;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ChatMessageResponse send(Long userId, ChatMessageRequest request) {
        AiMarketSession session = aiMarketSessionService.requireOwnedSession(
                userId, request.sessionId(), request.toolId());
        AiMarketTool tool = aiMarketToolService.requireToolForChat(request.toolId());
        String trimmed = request.content().trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "content 不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        List<AiMarketFile> files = aiMarketFileService.requireOwnedFiles(userId, request.attachments());
        List<MarketChatMessageDto> modelMessages = buildModelMessages(tool, session, trimmed);
        AiMarketMessage userMessage = persistMessage(
                request.sessionId(),
                "user",
                trimmed,
                request.params(),
                now
        );
        List<MessageAttachmentResponse> userAttachments = persistAttachments(userMessage.getMessageId(), files);

        maybeUpdateSessionTitle(session, trimmed, now);
        aiMarketSessionMapper.touch(session.getSessionId(), now);

        String assistantText = generateAssistantReply(tool, modelMessages, request.params());
        AiMarketMessage assistantMessage = persistMessage(
                request.sessionId(),
                "assistant",
                assistantText,
                null,
                LocalDateTime.now()
        );

        return new ChatMessageResponse(
                MessageResponse.from(userMessage, userAttachments, objectMapper),
                MessageResponse.from(assistantMessage, List.of(), objectMapper)
        );
    }

    private AiMarketMessage persistMessage(
            String sessionId,
            String role,
            String content,
            Map<String, Object> params,
            LocalDateTime createdAt
    ) {
        AiMarketMessage message = new AiMarketMessage();
        message.setMessageId(MarketIdGenerator.messageId());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setParamsJson(serializeParams(params));
        message.setCreatedAt(createdAt);
        aiMarketMessageMapper.insertMessage(message);
        return message;
    }

    private List<MessageAttachmentResponse> persistAttachments(String messageId, List<AiMarketFile> files) {
        List<MessageAttachmentResponse> responses = new ArrayList<>();
        for (AiMarketFile file : files) {
            AiMarketMessageAttachment attachment = new AiMarketMessageAttachment();
            attachment.setMessageId(messageId);
            attachment.setFileId(file.getFileId());
            attachment.setFileName(file.getOriginalName());
            attachment.setFileSize(file.getFileSize());
            attachment.setContentType(file.getContentType());
            aiMarketMessageAttachmentMapper.insertAttachment(attachment);
            responses.add(MessageAttachmentResponse.from(attachment));
        }
        return responses;
    }

    private void maybeUpdateSessionTitle(AiMarketSession session, String content, LocalDateTime now) {
        if (!DEFAULT_TITLE.equals(session.getTitle())) {
            return;
        }
        if (aiMarketSessionMapper.countUserMessages(session.getSessionId()) != 1) {
            return;
        }
        String title = content.length() <= 20 ? content : content.substring(0, 20);
        aiMarketSessionMapper.updateTitle(session.getSessionId(), title, now);
        session.setTitle(title);
    }

    private String generateAssistantReply(
            AiMarketTool tool,
            List<MarketChatMessageDto> messages,
            Map<String, Object> params
    ) {
        AgentModelConfig modelConfig = resolveModelConfig(tool);
        if (modelConfig == null || !appProperties.getAgent().isEnabled()) {
            String lastUser = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
            return fallbackReply(tool.getName(), lastUser, params);
        }
        try {
            MarketChatCompletionResponse response = agentServiceClient.marketChatCompletion(
                    new MarketChatCompletionRequest(
                            marketModelConfigBridge.toRequest(modelConfig),
                            messages
                    )
            );
            if (response == null || response.content() == null || response.content().isBlank()) {
                String lastUser = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
                return fallbackReply(tool.getName(), lastUser, params);
            }
            return response.content().trim();
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, "大模型调用失败：" + exception.getMessage());
        }
    }

    private AgentModelConfig resolveModelConfig(AiMarketTool tool) {
        if (tool.getModelConfigId() != null) {
            AgentModelConfig config = agentModelConfigMapper.findActiveById(tool.getModelConfigId());
            if (config != null) {
                return config;
            }
        }
        return agentModelConfigMapper.findLatest();
    }

    private List<MarketChatMessageDto> buildModelMessages(AiMarketTool tool, AiMarketSession session, String userContent) {
        List<MarketChatMessageDto> messages = new ArrayList<>();
        if (tool.getWelcomeMessage() != null && !tool.getWelcomeMessage().isBlank()) {
            messages.add(new MarketChatMessageDto("system", "你是「" + tool.getName() + "」。" + tool.getWelcomeMessage()));
        } else {
            messages.add(new MarketChatMessageDto("system", "你是「" + tool.getName() + "」，请简洁、友好地回答用户。"));
        }
        for (AiMarketMessage history : aiMarketMessageMapper.findBySessionId(session.getSessionId())) {
            messages.add(new MarketChatMessageDto(history.getRole(), history.getContent()));
        }
        messages.add(new MarketChatMessageDto("user", userContent));
        return messages;
    }

    private String fallbackReply(String toolName, String userContent, Map<String, Object> params) {
        StringBuilder builder = new StringBuilder("【").append(toolName).append("】");
        if (params != null && !params.isEmpty()) {
            builder.append("（已收到参数：").append(params).append("）");
        }
        builder.append("\n\n已收到你的消息：").append(userContent);
        if (!appProperties.getAgent().isEnabled()) {
            builder.append("\n\n（当前环境未启用 Agent 服务，此为占位回复。）");
        }
        return builder.toString();
    }

    private String serializeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(params));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "params 格式无效");
        }
    }
}
