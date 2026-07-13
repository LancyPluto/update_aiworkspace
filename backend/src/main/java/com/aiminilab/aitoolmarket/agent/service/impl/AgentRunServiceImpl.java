package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.config.AgentMemorySettings;
import com.aiminilab.aitoolmarket.agent.config.AgentPromptSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRouterSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRuntimeSettings;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRouterSettingsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.BindAgentToolCallTaskRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpsertStreamingAgentAnswerRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.ConfirmAgentToolRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.EditRegenerateAgentMessageRequest;
import com.aiminilab.aitoolmarket.agent.dto.RegenerateAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentFileChunkContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentFileContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalRecentToolCallContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalPendingToolContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalReferenceMentionResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolPreferenceRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.aiminilab.aitoolmarket.agent.entity.AgentFileChunk;
import com.aiminilab.aitoolmarket.agent.entity.AgentContextSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;
import com.aiminilab.aitoolmarket.agent.entity.AgentPendingToolContext;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;
import com.aiminilab.aitoolmarket.agent.entity.AgentSession;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileChunkMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentContextSnapshotMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentMessageMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentPendingToolContextMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSessionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.metrics.AgentMetrics;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentRateLimitService;
import com.aiminilab.aitoolmarket.agent.service.AgentFileService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentSkillBundleService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolPreferenceService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.credit.support.CreditInsufficientSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.aiminilab.aitoolmarket.agent.support.AgentAuditRedactor;

@Service
public class AgentRunServiceImpl implements AgentRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunServiceImpl.class);
    private static final int FILE_CONTEXT_LIMIT = 5;
    private static final int FILE_CHUNK_SCAN_LIMIT = 200;
    private static final int FILE_CHUNK_CONTEXT_LIMIT = 5;
    private static final int RECENT_TOOL_RESULT_CONTEXT_LIMIT = 5;
    private static final int MAX_TOOL_RESULT_CONTEXT_PREVIEW_LENGTH = 800;
    private static final int MAX_HISTORY_MESSAGE_CONTEXT_LENGTH = 12000;
    private static final int DEFAULT_EVENT_PAGE_SIZE = 100;
    private static final int MAX_EVENT_TEXT_LENGTH = 4000;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 4000;
    private static final String EVENT_TEXT_TRUNCATED_SUFFIX = "... [truncated]";
    private static final long EVENT_STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final Set<String> CANCELLABLE_STATUSES = Set.of("CREATED", "RUNNING", "WAITING_USER_CONFIRMATION");
    private static final Set<String> CONFIRMABLE_STATUSES = Set.of("WAITING_USER_CONFIRMATION");
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT");
    private static final Set<String> TOOL_CALL_TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED");
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> eventStreams = new ConcurrentHashMap<>();

    private final AgentSessionMapper agentSessionMapper;
    private final AgentMessageMapper agentMessageMapper;
    private final AgentFileMapper agentFileMapper;
    private final AgentFileService agentFileService;
    private final AgentFileChunkMapper agentFileChunkMapper;
    private final AgentContextSnapshotMapper agentContextSnapshotMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentRunEventMapper agentRunEventMapper;
    private final AgentToolCallMapper agentToolCallMapper;
    private final AgentToolPreferenceMapper agentToolPreferenceMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentPendingToolContextMapper agentPendingToolContextMapper;
    private final AgentRateLimitService agentRateLimitService;
    private final AgentToolDescriptorService agentToolDescriptorService;
    private final AgentSkillBundleService agentSkillBundleService;
    private final AgentToolPreferenceService agentToolPreferenceService;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentServiceClient agentServiceClient;
    private final CreditService creditService;
    private final PricingService pricingService;
    private final BillingService billingService;
    private final SystemSettingService systemSettingService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final AgentMetrics agentMetrics;
    private final AgentAuditRedactor agentAuditRedactor;

    public AgentRunServiceImpl(
            AgentSessionMapper agentSessionMapper,
            AgentMessageMapper agentMessageMapper,
            AgentFileMapper agentFileMapper,
            AgentFileService agentFileService,
            AgentFileChunkMapper agentFileChunkMapper,
            AgentContextSnapshotMapper agentContextSnapshotMapper,
            AgentRunMapper agentRunMapper,
            AgentRunEventMapper agentRunEventMapper,
            AgentToolCallMapper agentToolCallMapper,
            AgentToolPreferenceMapper agentToolPreferenceMapper,
            AgentModelConfigMapper agentModelConfigMapper,
            AgentPendingToolContextMapper agentPendingToolContextMapper,
            AgentRateLimitService agentRateLimitService,
            AgentToolDescriptorService agentToolDescriptorService,
            AgentSkillBundleService agentSkillBundleService,
            AgentToolPreferenceService agentToolPreferenceService,
            AgentModelConfigService agentModelConfigService,
            AgentServiceClient agentServiceClient,
            CreditService creditService,
            PricingService pricingService,
            BillingService billingService,
            SystemSettingService systemSettingService,
            AppProperties appProperties,
            ObjectMapper objectMapper,
            AgentMetrics agentMetrics,
            AgentAuditRedactor agentAuditRedactor
    ) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentMessageMapper = agentMessageMapper;
        this.agentFileMapper = agentFileMapper;
        this.agentFileService = agentFileService;
        this.agentFileChunkMapper = agentFileChunkMapper;
        this.agentContextSnapshotMapper = agentContextSnapshotMapper;
        this.agentRunMapper = agentRunMapper;
        this.agentRunEventMapper = agentRunEventMapper;
        this.agentToolCallMapper = agentToolCallMapper;
        this.agentToolPreferenceMapper = agentToolPreferenceMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentPendingToolContextMapper = agentPendingToolContextMapper;
        this.agentRateLimitService = agentRateLimitService;
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.agentSkillBundleService = agentSkillBundleService;
        this.agentToolPreferenceService = agentToolPreferenceService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentServiceClient = agentServiceClient;
        this.creditService = creditService;
        this.pricingService = pricingService;
        this.billingService = billingService;
        this.systemSettingService = systemSettingService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.agentMetrics = agentMetrics;
        this.agentAuditRedactor = agentAuditRedactor;
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public CreateAgentMessageResponse sendMessage(Long userId, Long sessionId, CreateAgentMessageRequest request) {
        AgentSession session = findSession(userId, sessionId);
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不可用");
        }
        String clientKey = normalizeClientRequestId(request.clientRequestId());
        if (clientKey != null) {
            CreateAgentMessageResponse idempotent = tryIdempotentAgentRun(userId, clientKey);
            if (idempotent != null) {
                return idempotent;
            }
        }
        agentRateLimitService.checkMessageRate(userId);
        agentRateLimitService.checkRunRate(userId);
        agentRateLimitService.checkActiveRunLimit(userId);
        int creditBudget = Math.max(0, appProperties.getAgent().getDefaultCreditBudget());
        CreditInsufficientSupport.ensureAvailable(
                creditService, userId, creditBudget, ErrorCode.AGENT_CREDIT_NOT_ENOUGH, null);

        LocalDateTime now = LocalDateTime.now();
        String trimmed = request.content().trim();
        Long parentMessageId = resolveParentMessageId(userId, session, request.parentMessageId());
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole("USER");
        message.setContentText(trimmed);
        message.setParentMessageId(parentMessageId);
        message.setStatus("ACTIVE");
        message.setCreatedAt(now);
        String contentJson = messageContentJson(
                request.urlAttachments(),
                request.intelligenceLevel(),
                request.referenceMentions(),
                request.globalFileIds(),
                request.contentParts(),
                request.positionalPrompt()
        );
        if (contentJson != null) {
            message.setContentJson(contentJson);
        }
        agentMessageMapper.insertMessage(message);

        String preferredToolCode = normalizePreferredToolCode(request.preferredToolCode());
        if (preferredToolCode != null) {
            agentToolDescriptorService.getToolForAgent(userId, preferredToolCode);
        }

        return executeStartRun(
                userId,
                session,
                message,
                null,
                clientKey,
                trimmed,
                now,
                request.fileIds(),
                request.modelConfigId(),
                preferredToolCode
        );
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public CreateAgentMessageResponse regenerateRun(Long userId, Long runId, RegenerateAgentRunRequest request) {
        RegenerateAgentRunRequest body = request == null ? new RegenerateAgentRunRequest(null, null) : request;
        String clientKey = normalizeClientRequestId(body.clientRequestId());
        if (clientKey != null) {
            CreateAgentMessageResponse idempotent = tryIdempotentAgentRun(userId, clientKey);
            if (idempotent != null) {
                return idempotent;
            }
        }
        AgentRun sourceRun = findRun(runId, userId);
        if (!TERMINAL_STATUSES.contains(sourceRun.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_REGENERATABLE, "当前运行不可重新生成");
        }
        AgentMessage userMessage = resolveUserMessageForRun(sourceRun);
        if (userMessage == null) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "无法解析该运行的用户消息");
        }
        if (!"ACTIVE".equals(userMessage.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "消息不存在或已失效");
        }
        AgentSession session = findSession(userId, sourceRun.getSessionId());
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不可用");
        }
        agentRateLimitService.checkRunRate(userId);
        agentRateLimitService.checkActiveRunLimit(userId);
        int creditBudget = Math.max(0, appProperties.getAgent().getDefaultCreditBudget());
        CreditInsufficientSupport.ensureAvailable(
                creditService, userId, creditBudget, ErrorCode.AGENT_CREDIT_NOT_ENOUGH, null);

        LocalDateTime now = LocalDateTime.now();
        return executeStartRun(userId, session, userMessage, runId, clientKey, null, now, null, body.modelConfigId(), null);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public CreateAgentMessageResponse editRegenerateMessage(Long userId, Long sessionId, Long messageId,
                                                           EditRegenerateAgentMessageRequest request) {
        String clientKey = normalizeClientRequestId(request.clientRequestId());
        if (clientKey != null) {
            CreateAgentMessageResponse idempotent = tryIdempotentAgentRun(userId, clientKey);
            if (idempotent != null) {
                return idempotent;
            }
        }
        AgentSession session = findSession(userId, sessionId);
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不可用");
        }
        AgentMessage userMessage = agentMessageMapper.findByIdSessionAndUser(messageId, sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "消息不存在"));
        if (!"USER".equals(userMessage.getRole())) {
            throw new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_EDITABLE, "仅支持编辑用户消息");
        }
        if (!"ACTIVE".equals(userMessage.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "消息不存在或已失效");
        }
        String trimmed = request.content().trim();
        if (trimmed.equals(userMessage.getContentText())) {
            throw new BusinessException(ErrorCode.AGENT_USE_REGENERATE_PATH, "内容未变化，请使用「重新生成」接口");
        }
        if (agentRunMapper.countActiveRunsBySession(sessionId) > 0) {
            throw new BusinessException(ErrorCode.AGENT_ACTIVE_RUN_EXISTS, "会话中有进行中的运行，请先等待完成或取消");
        }
        agentRateLimitService.checkMessageRate(userId);
        agentRateLimitService.checkRunRate(userId);
        agentRateLimitService.checkActiveRunLimit(userId);
        int creditBudget = Math.max(0, appProperties.getAgent().getDefaultCreditBudget());
        CreditInsufficientSupport.ensureAvailable(
                creditService, userId, creditBudget, ErrorCode.AGENT_CREDIT_NOT_ENOUGH, null);

        LocalDateTime now = LocalDateTime.now();
        AgentMessage editedMessage = new AgentMessage();
        editedMessage.setSessionId(sessionId);
        editedMessage.setUserId(userId);
        editedMessage.setRole("USER");
        editedMessage.setContentText(trimmed);
        editedMessage.setContentJson(editedMessageContentJson(userMessage.getContentJson(), trimmed));
        editedMessage.setParentMessageId(userMessage.getParentMessageId());
        editedMessage.setStatus("ACTIVE");
        editedMessage.setEditedAt(now);
        editedMessage.setCreatedAt(now);
        agentMessageMapper.insertMessage(editedMessage);

        return executeStartRun(userId, session, editedMessage, userMessage.getRunId(), clientKey, trimmed, now, null, request.modelConfigId(), null);
    }

    @Override
    public AgentRunResponse detail(Long userId, Long runId) {
        return AgentRunResponse.from(findRun(runId, userId));
    }

    @Override
    @Transactional
    public AgentRunResponse cancel(Long userId, Long runId) {
        AgentRun run = findRun(runId, userId);
        if (!CANCELLABLE_STATUSES.contains(run.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_CANCELLABLE, "当前 Agent 运行不可取消");
        }
        LocalDateTime now = LocalDateTime.now();
        if (agentRunMapper.markCancelled(runId, now) == 0) {
            return AgentRunResponse.from(findRun(runId, userId));
        }
        failOpenToolCalls(runId, userId, "RUN_CANCELLED", "Agent 运行已取消", now);
        creditService.release(userId, CreditSourceType.AGENT_RUN, runId, Math.max(0, run.getEstimatedCredits()));
        agentRateLimitService.decrementActiveRun(userId, runId);
        appendEventInternal(runId, userId, "run.failed", "Agent 运行已取消", "{\"status\":\"CANCELLED\"}", now);
        agentMetrics.recordRunOutcome("CANCELLED", run.getIntent(), firstNonNull(run.getStartedAt(), run.getCreatedAt()), now);
        cleanupEventStreams(runId);
        return AgentRunResponse.from(findRun(runId, userId));
    }

    @Override
    @Transactional
    public AgentRunResponse confirmTool(Long userId, Long runId, ConfirmAgentToolRequest request) {
        AgentRun run = findRun(runId, userId);
        if (!CONFIRMABLE_STATUSES.contains(run.getStatus())) {
            if ("RUNNING".equals(run.getStatus())) {
                return AgentRunResponse.from(run);
            }
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_CANCELLABLE, "当前 Agent 运行不可确认工具调用");
        }
        if (Boolean.FALSE.equals(request.approved())) {
            LocalDateTime now = LocalDateTime.now();
            if (agentRunMapper.markCancelled(runId, now) == 0) {
                return AgentRunResponse.from(findRun(runId, userId));
            }
            failOpenToolCalls(runId, userId, "TOOL_CONFIRMATION_REJECTED", "用户取消工具调用", now);
            creditService.release(userId, CreditSourceType.AGENT_RUN, runId, Math.max(0, run.getEstimatedCredits()));
            agentRateLimitService.decrementActiveRun(userId, runId);
            appendEventInternal(runId, userId, "run.failed", "用户取消工具调用", "{\"status\":\"CANCELLED\"}", now);
            agentMetrics.recordRunOutcome("CANCELLED", run.getIntent(), firstNonNull(run.getStartedAt(), run.getCreatedAt()), now);
            cleanupEventStreams(runId);
            return AgentRunResponse.from(findRun(runId, userId));
        }
        agentToolDescriptorService.getToolForAgent(userId, request.toolCode());
        LocalDateTime now = LocalDateTime.now();
        if (request.autoCallEnabled() != null) {
            agentToolPreferenceService.update(userId, request.toolCode(), new UpdateAgentToolPreferenceRequest(request.autoCallEnabled(), null));
        }
        if (agentRunMapper.markRunningIfStatus(runId, "WAITING_USER_CONFIRMATION", now) == 0) {
            return AgentRunResponse.from(findRun(runId, userId));
        }
        appendEventInternal(runId, userId, "tool.confirmed", request.toolCode(), "{\"toolCode\":\"" + request.toolCode() + "\"}", now);
        runAfterCommit(() -> notifyAgentService(run.getId(), () -> agentServiceClient.confirmTool(run.getId(), request.toolCode())));
        return AgentRunResponse.from(findRun(runId, userId));
    }

    @Override
    public PageResponse<AgentRunEventResponse> events(Long userId, Long runId, Long afterEventId, Integer pageSize) {
        findRun(runId, userId);
        int limit = pageSize == null || pageSize < 1 ? DEFAULT_EVENT_PAGE_SIZE : Math.min(pageSize, 200);
        List<AgentRunEventResponse> events = agentRunEventMapper.findEvents(userId, runId, afterEventId, limit)
                .stream()
                .map(AgentRunEventResponse::from)
                .toList();
        return new PageResponse<>(events, events.size(), 1, limit, events.size() == limit);
    }

    @Override
    public SseEmitter streamEvents(Long userId, Long runId, Long afterEventId) {
        findRun(runId, userId);
        SseEmitter emitter = new SseEmitter(EVENT_STREAM_TIMEOUT_MILLIS);
        CopyOnWriteArrayList<SseEmitter> emitters = eventStreams.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(runId, emitter));
        emitter.onTimeout(() -> removeEmitter(runId, emitter));
        emitter.onError(ignored -> removeEmitter(runId, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
            List<AgentRunEventResponse> replayEvents = agentRunEventMapper.findEvents(userId, runId, afterEventId, DEFAULT_EVENT_PAGE_SIZE)
                    .stream()
                    .map(AgentRunEventResponse::from)
                    .toList();
            replayEvents.forEach(event -> sendEvent(runId, emitter, event));
            if (replayEvents.stream().anyMatch(this::isTerminalRunEvent)) {
                removeEmitter(runId, emitter);
                emitter.complete();
            }
        } catch (IOException exception) {
            removeEmitter(runId, emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @Override
    public InternalAgentRunContextResponse context(Long runId) {
        AgentRun run = findRun(runId);
        AgentMessage userMessage = agentMessageMapper.findUserMessageByRunId(runId);
        if (userMessage == null && run.getSourceUserMessageId() != null) {
            userMessage = agentMessageMapper.selectById(run.getSourceUserMessageId());
        }
        Long anchorId = run.getSourceUserMessageId();
        if (anchorId == null && userMessage != null) {
            anchorId = userMessage.getId();
        }
        List<Long> branchRunIds = branchRunIdsForRun(run, userMessage, anchorId);
        List<InternalAgentMessageResponse> history;
        if (anchorId == null) {
            history = List.of();
        } else {
            history = new java.util.ArrayList<>(activePathBeforeUserMessage(run.getSessionId(), anchorId, contextHistoryLimit())
                    .stream()
                    .map(message -> new InternalAgentMessageResponse(
                            message.getRole(),
                            safeContextMessageText(message.getContentText(), MAX_HISTORY_MESSAGE_CONTEXT_LENGTH)
                    ))
                    .toList());
            recentToolResultContext(run, branchRunIds).ifPresent(summary ->
                    history.add(new InternalAgentMessageResponse("system", summary))
            );
        }
        List<AgentFile> readyFiles = agentFileMapper.findReadyByRun(
                run.getUserId(),
                run.getSessionId(),
                run.getId(),
                FILE_CONTEXT_LIMIT
        );
        Map<Long, String> filenames = readyFiles.stream()
                .collect(java.util.stream.Collectors.toMap(AgentFile::getId, AgentFile::getOriginalFilename));
        List<InternalReferenceMentionResponse> referenceMentions = referenceMentionContexts(userMessage);
        List<Object> globalFileIds = globalFileIdsFromMessage(userMessage);
        List<Map<String, Object>> contentParts = contentPartsFromMessage(userMessage);
        String positionalPrompt = positionalPromptFromMessage(userMessage);
        List<InternalAgentFileContextResponse> agentFiles = mergeAgentFileContexts(
                urlAttachmentContexts(userMessage),
                referenceMentionFileContexts(referenceMentions),
                readyFiles
                        .stream()
                        .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                        .map(InternalAgentFileContextResponse::from)
                        .toList()
        );
        List<InternalAgentFileChunkContextResponse> agentFileChunks = retrieveRelevantFileChunks(
                run,
                userMessage == null ? "" : userMessage.getContentText(),
                filenames
        );
        List<AgentToolDescriptorResponse> tools = agentToolDescriptorService.listAvailableToolsForUser(run.getUserId());
        var availableSkills = agentSkillBundleService.listAvailableSkillDescriptors(tools);
        var preferences = agentToolPreferenceMapper.findByUserId(run.getUserId())
                .stream()
                .map(com.aiminilab.aitoolmarket.agent.dto.AgentToolPreferenceResponse::from)
                .toList();
        Map<String, String> settings = systemSettingService.settings();
        String intelligenceLevel = intelligenceLevelFromMessage(userMessage);
        boolean highIntelligence = "high".equals(intelligenceLevel);
        int maxModelCalls = parseIntSetting(settings.get(AgentRuntimeSettings.MAX_MODEL_CALLS_KEY), AgentRuntimeSettings.DEFAULT_MAX_MODEL_CALLS, 1, 50);
        int maxHistoryMessages = parseIntSetting(settings.get(AgentRuntimeSettings.MAX_HISTORY_MESSAGES_KEY), AgentRuntimeSettings.DEFAULT_MAX_HISTORY_MESSAGES, 1, 100);
        int routerHistoryTurns = parseIntSetting(settings.get(AgentRouterSettings.HISTORY_TURNS_KEY), AgentRouterSettings.DEFAULT_HISTORY_TURNS, 0, 20);
        int recentToolCallLimit = parseIntSetting(settings.get(AgentRouterSettings.RECENT_TOOL_CALLS_KEY), AgentRouterSettings.DEFAULT_RECENT_TOOL_CALLS, 0, 10);
        if (highIntelligence) {
            maxModelCalls = Math.max(maxModelCalls, 8);
            maxHistoryMessages = Math.max(maxHistoryMessages, 32);
            routerHistoryTurns = Math.max(routerHistoryTurns, 8);
            recentToolCallLimit = Math.max(recentToolCallLimit, 8);
        }
        var runtimeSettings = new com.aiminilab.aitoolmarket.agent.dto.AgentRuntimeSettingsResponse(
                maxModelCalls,
                parseIntSetting(settings.get(AgentRuntimeSettings.MAX_TOOL_CALLS_KEY), AgentRuntimeSettings.DEFAULT_MAX_TOOL_CALLS, 1, 50),
                maxHistoryMessages,
                parseIntSetting(settings.get(AgentRuntimeSettings.WORKING_MEMORY_TOKEN_BUDGET_KEY), AgentRuntimeSettings.DEFAULT_WORKING_MEMORY_TOKEN_BUDGET, 500, 50000),
                parseIntSetting(settings.get(AgentRuntimeSettings.MESSAGE_TOKEN_SOFT_LIMIT_KEY), AgentRuntimeSettings.DEFAULT_MESSAGE_TOKEN_SOFT_LIMIT, 100, 10000),
                parseIntSetting(settings.get(AgentRuntimeSettings.TOOL_OUTPUT_TOKEN_SOFT_LIMIT_KEY), AgentRuntimeSettings.DEFAULT_TOOL_OUTPUT_TOKEN_SOFT_LIMIT, 100, 10000),
                parseIntSetting(settings.get(AgentRuntimeSettings.SUMMARY_TOKEN_LIMIT_KEY), AgentRuntimeSettings.DEFAULT_SUMMARY_TOKEN_LIMIT, 100, 10000),
                parseIntSetting(settings.get(AgentRuntimeSettings.TOOL_EXECUTION_TIMEOUT_SECONDS_KEY), AgentRuntimeSettings.DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS, 10, 3600),
                parseIntSetting(settings.get(AgentRuntimeSettings.IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY), AgentRuntimeSettings.DEFAULT_IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS, 10, 3600),
                parseIntSetting(settings.get(AgentRuntimeSettings.VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY), AgentRuntimeSettings.DEFAULT_VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS, 10, 7200),
                parseIntSetting(settings.get(AgentRuntimeSettings.MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY), AgentRuntimeSettings.DEFAULT_MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS, 10, 7200),
                parseDoubleSetting(settings.get(AgentRuntimeSettings.TOOL_POLL_INTERVAL_SECONDS_KEY), AgentRuntimeSettings.DEFAULT_TOOL_POLL_INTERVAL_SECONDS, 0.2D, 30D),
                parseBooleanSetting(settings.get(AgentRuntimeSettings.TOOL_STREAM_RELAY_ENABLED_KEY), AgentRuntimeSettings.DEFAULT_TOOL_STREAM_RELAY_ENABLED),
                parseBooleanSetting(settings.get(AgentRuntimeSettings.PRODUCT_TOOL_LOOP_ENABLED_KEY), AgentRuntimeSettings.DEFAULT_PRODUCT_TOOL_LOOP_ENABLED),
                parseIntSetting(settings.get(AgentRuntimeSettings.PRODUCT_TOOL_LOOP_MAX_CALLS_KEY), AgentRuntimeSettings.DEFAULT_PRODUCT_TOOL_LOOP_MAX_CALLS, 1, 20),
                parseBooleanSetting(settings.get(AgentRuntimeSettings.PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER_KEY), AgentRuntimeSettings.DEFAULT_PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER),
                intelligenceLevel
        );
        String agentSystemPrompt = nonBlankOrDefault(
                settings.get(AgentPromptSettings.SYSTEM_PROMPT_KEY),
                AgentPromptSettings.DEFAULT_SYSTEM_PROMPT
        );
        String deepAgentsSystemPrompt = nonBlankOrDefault(
                settings.get(AgentPromptSettings.DEEP_AGENTS_SYSTEM_PROMPT_KEY),
                AgentPromptSettings.DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT
        );
        var memorySettings = new com.aiminilab.aitoolmarket.agent.dto.AgentMemorySettingsResponse(
                parseBooleanSetting(settings.get(AgentMemorySettings.AUTO_SAVE_ENABLED_KEY), AgentMemorySettings.DEFAULT_AUTO_SAVE_ENABLED),
                parseIntSetting(settings.get(AgentMemorySettings.RETRIEVAL_LIMIT_KEY), AgentMemorySettings.DEFAULT_RETRIEVAL_LIMIT, 1, 20),
                parseCsvSetting(settings.get(AgentMemorySettings.ENABLED_TYPES_KEY), AgentMemorySettings.DEFAULT_ENABLED_TYPES),
                nonBlankOrDefault(settings.get(AgentMemorySettings.WRITE_PROMPT_KEY), AgentMemorySettings.DEFAULT_WRITE_PROMPT),
                nonBlankOrDefault(settings.get(AgentMemorySettings.RETRIEVAL_PROMPT_KEY), AgentMemorySettings.DEFAULT_RETRIEVAL_PROMPT),
                parseBooleanSetting(settings.get(AgentMemorySettings.TOOL_LOOP_ENABLED_KEY), AgentMemorySettings.DEFAULT_TOOL_LOOP_ENABLED),
                parseBooleanSetting(settings.get(AgentMemorySettings.CONSOLIDATION_ENABLED_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_ENABLED),
                parseBooleanSetting(settings.get(AgentMemorySettings.CONSOLIDATION_LLM_ENABLED_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_LLM_ENABLED),
                parseIntSetting(settings.get(AgentMemorySettings.CONSOLIDATION_TURN_INTERVAL_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_TURN_INTERVAL, 2, 50),
                parseIntSetting(settings.get(AgentMemorySettings.CONSOLIDATION_CHAR_THRESHOLD_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_CHAR_THRESHOLD, 500, 50000),
                parseIntSetting(settings.get(AgentMemorySettings.CONSOLIDATION_TOKEN_THRESHOLD_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_TOKEN_THRESHOLD, 0, 200000),
                parseIntSetting(settings.get(AgentMemorySettings.CONSOLIDATION_RECENT_TOOL_THRESHOLD_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_RECENT_TOOL_THRESHOLD, 0, 50),
                parseIntSetting(settings.get(AgentMemorySettings.CONSOLIDATION_MAX_CONTEXT_MESSAGES_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_MAX_CONTEXT_MESSAGES, 4, 100),
                nonBlankOrDefault(settings.get(AgentMemorySettings.CONSOLIDATION_PROMPT_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_PROMPT),
                parseDoubleSetting(settings.get(AgentMemorySettings.CONSOLIDATION_MIN_CONFIDENCE_KEY), AgentMemorySettings.DEFAULT_CONSOLIDATION_MIN_CONFIDENCE, 0D, 1D),
                parseDoubleSetting(settings.get(AgentMemorySettings.CANDIDATE_CONFIDENCE_THRESHOLD_KEY), AgentMemorySettings.DEFAULT_CANDIDATE_CONFIDENCE_THRESHOLD, 0D, 1D)
        );
        var routerSettings = new AgentRouterSettingsResponse(
                parseBooleanSetting(settings.get(AgentRouterSettings.ENABLED_KEY), AgentRouterSettings.DEFAULT_ENABLED),
                nonBlankOrDefault(settings.get(AgentRouterSettings.PROMPT_KEY), AgentRouterSettings.DEFAULT_PROMPT),
                parseDoubleSetting(settings.get(AgentRouterSettings.MIN_CONFIDENCE_KEY), 0.7D, 0D, 1D),
                parseBooleanSetting(settings.get(AgentRouterSettings.FALLBACK_TO_RULES_KEY), AgentRouterSettings.DEFAULT_FALLBACK_TO_RULES),
                routerHistoryTurns,
                recentToolCallLimit
        );
        InternalPendingToolContextResponse pendingToolContextResponse = null;
        AgentPendingToolContext pendingCtx = agentPendingToolContextMapper.findActiveByRunId(runId);
        if (pendingCtx == null) {
            pendingCtx = agentPendingToolContextMapper.findActiveBySessionId(run.getSessionId());
        }
        if (pendingCtx != null) {
            pendingToolContextResponse = new InternalPendingToolContextResponse(
                    pendingCtx.getId(),
                    pendingCtx.getRunId(),
                    pendingCtx.getSessionId(),
                    pendingCtx.getUserId(),
                    pendingCtx.getSelectedToolCode(),
                    pendingCtx.getCandidateToolCodesJson(),
                    pendingCtx.getCollectedArgumentsJson(),
                    pendingCtx.getMissingArgumentsJson(),
                    pendingCtx.getClarifyingQuestion(),
                    pendingCtx.getConfirmationRequired(),
                    pendingCtx.getStatus()
            );
        }
        AgentSession session = findSession(run.getUserId(), run.getSessionId());
        return new InternalAgentRunContextResponse(
                run.getId(),
                run.getSessionId(),
                session.getWorkspaceId(),
                run.getUserId(),
                run.getStatus(),
                userMessage == null ? "" : userMessage.getContentText(),
                history,
                session.getConversationSummary(),
                agentFiles,
                agentFileChunks,
                tools,
                availableSkills,
                preferences,
                run.getEstimatedCredits(),
                com.aiminilab.aitoolmarket.agent.dto.AgentContextWindowResponse.from(
                        agentContextSnapshotMapper.findLatestByRunId(runId)
                ),
                resolveModelConfigForRun(run),
                agentSystemPrompt,
                deepAgentsSystemPrompt,
                memorySettings,
                routerSettings,
                runtimeSettings,
                recentToolCallContext(run, branchRunIds),
                pendingToolContextResponse,
                run.getPreferredToolCode(),
                referenceMentions,
                globalFileIds,
                contentParts,
                positionalPrompt
        );
    }

    private List<InternalAgentFileContextResponse> mergeAgentFileContexts(
            List<InternalAgentFileContextResponse> urlAttachments,
            List<InternalAgentFileContextResponse> referenceMentionFiles,
            List<InternalAgentFileContextResponse> uploadedFiles
    ) {
        List<InternalAgentFileContextResponse> merged = new java.util.ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.LinkedHashSet<>();
        for (InternalAgentFileContextResponse file : urlAttachments) {
            if (file == null) {
                continue;
            }
            String normalizedUrl = normalizeAgentFileUrl(file.downloadUrl());
            if (normalizedUrl.isBlank() || !seenUrls.add(normalizedUrl)) {
                continue;
            }
            merged.add(file);
            if (merged.size() >= FILE_CONTEXT_LIMIT) {
                return merged;
            }
        }
        for (InternalAgentFileContextResponse file : referenceMentionFiles) {
            if (file == null) {
                continue;
            }
            String normalizedUrl = normalizeAgentFileUrl(file.downloadUrl());
            if (normalizedUrl.isBlank() || !seenUrls.add(normalizedUrl)) {
                continue;
            }
            merged.add(file);
            if (merged.size() >= FILE_CONTEXT_LIMIT) {
                return merged;
            }
        }
        for (InternalAgentFileContextResponse file : uploadedFiles) {
            if (file == null) {
                continue;
            }
            String normalizedUrl = normalizeAgentFileUrl(file.downloadUrl());
            if (!normalizedUrl.isBlank() && !seenUrls.add(normalizedUrl)) {
                continue;
            }
            merged.add(file);
            if (merged.size() >= FILE_CONTEXT_LIMIT) {
                return merged;
            }
        }
        return merged;
    }

    private List<InternalAgentFileContextResponse> referenceMentionFileContexts(
            List<InternalReferenceMentionResponse> mentions
    ) {
        if (mentions == null || mentions.isEmpty()) {
            return List.of();
        }
        List<InternalAgentFileContextResponse> contexts = new java.util.ArrayList<>();
        long syntheticId = -1L;
        for (InternalReferenceMentionResponse mention : mentions) {
            if (mention == null) {
                continue;
            }
            String url = mention.url() == null ? "" : mention.url().trim();
            if (!isAllowedMaterialUrl(url)) {
                continue;
            }
            String label = nonBlankOrDefault(mention.refLabel(), nonBlankOrDefault(mention.token(), "素材附件"));
            String contentType = referenceMentionContentType(mention.kind());
            contexts.add(InternalAgentFileContextResponse.urlAttachment(syntheticId--, label, contentType, url));
        }
        return contexts;
    }

    private String referenceMentionContentType(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase();
        return switch (normalized) {
            case "video" -> "video/*";
            case "audio" -> "audio/*";
            case "document", "file" -> "application/octet-stream";
            default -> "image/*";
        };
    }

    private String normalizeAgentFileUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.trim();
    }

    private String normalizePreferredToolCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String referenceAttachmentLabel(int index, String name, String contentType) {
        if (!isImageAttachmentName(name, contentType)) {
            return nonBlankOrDefault(name, "素材附件");
        }
        String cleaned = nonBlankOrDefault(name, "图片").replaceFirst("^@[^-]+-", "").trim();
        if (cleaned.length() > 14) {
            cleaned = cleaned.substring(0, 14) + "…";
        }
        return "@图片" + (index + 1) + "-" + cleaned;
    }

    private boolean isImageAttachmentName(String name, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("image/")) {
            return true;
        }
        String lowerName = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif");
    }

    private boolean parseBooleanSetting(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private int parseIntSetting(String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDoubleSetting(String value, double fallback, double min, double max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private List<String> parseCsvSetting(String value, String fallback) {
        String source = value == null || value.isBlank() ? fallback : value;
        return Arrays.stream(source.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private boolean hasCapability(List<String> capabilities, String capability) {
        if (capabilities == null || capabilities.isEmpty() || capability == null || capability.isBlank()) {
            return false;
        }
        return capabilities.stream()
                .filter(item -> item != null && !item.isBlank())
                .anyMatch(item -> item.trim().equalsIgnoreCase(capability));
    }

    private List<InternalAgentFileChunkContextResponse> retrieveRelevantFileChunks(
            AgentRun run,
            String query,
            Map<Long, String> filenames
    ) {
        List<AgentFileChunk> chunks = agentFileChunkMapper.findReadyByRun(
                        run.getUserId(),
                        run.getSessionId(),
                        run.getId(),
                        FILE_CHUNK_SCAN_LIMIT
                );
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = tokenize(query);
        List<ScoredFileChunk> scored = chunks.stream()
                .map(chunk -> new ScoredFileChunk(chunk, scoreChunk(query, queryTerms, chunk)))
                .filter(chunk -> chunk.score() > 0)
                .sorted(Comparator.comparingInt(ScoredFileChunk::score).reversed()
                        .thenComparing(chunk -> chunk.chunk().getFileId(), Comparator.reverseOrder())
                        .thenComparing(chunk -> chunk.chunk().getChunkIndex()))
                .limit(FILE_CHUNK_CONTEXT_LIMIT)
                .toList();
        if (scored.isEmpty()) {
            scored = chunks.stream()
                    .limit(FILE_CHUNK_CONTEXT_LIMIT)
                    .map(chunk -> new ScoredFileChunk(chunk, 0))
                    .toList();
        }
        return scored.stream()
                .map(scoredChunk -> {
                    AgentFileChunk chunk = scoredChunk.chunk();
                    return new InternalAgentFileChunkContextResponse(
                            chunk.getId(),
                            chunk.getFileId(),
                            filenames.getOrDefault(chunk.getFileId(), ""),
                            chunk.getChunkIndex(),
                            chunk.getContentText(),
                            chunk.getMetadataJson(),
                            scoredChunk.score()
                    );
                })
                .toList();
    }

    private int scoreChunk(String query, List<String> queryTerms, AgentFileChunk chunk) {
        String content = chunk.getContentText() == null ? "" : chunk.getContentText().toLowerCase();
        int score = 0;
        String normalizedQuery = query == null ? "" : query.toLowerCase().trim();
        if (!normalizedQuery.isEmpty() && content.contains(normalizedQuery)) {
            score += 5;
        }
        for (String term : queryTerms) {
            if (content.contains(term)) {
                score += 2;
            }
        }
        return score;
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(query.toLowerCase().split("[^\\p{IsAlphabetic}\\p{IsDigit}]+"))
                .map(String::trim)
                .filter(value -> value.length() >= 3)
                .distinct()
                .toList();
    }

    private record ScoredFileChunk(AgentFileChunk chunk, int score) {
    }

    @Override
    @Transactional
    public AgentRunEventResponse appendEvent(Long runId, CreateAgentRunEventRequest request) {
        AgentRun run = findRun(runId);
        LocalDateTime now = LocalDateTime.now();
        if ("tool.confirmation_required".equals(request.eventType())) {
            agentRunMapper.markWaitingForConfirmationIfStatus(runId, "RUNNING", now);
            persistPendingToolContextFromConfirmation(run, request.eventJson(), now);
        }
        if ("tool.missing_arguments".equals(request.eventType())) {
            persistPendingToolContextFromMissingArguments(run, request.eventJson(), now);
        }
        if ("intent.detected".equals(request.eventType())) {
            persistPendingToolContextFromIntentDetected(run, request.eventJson(), now);
        }
        return AgentRunEventResponse.from(appendEventInternal(
                runId,
                run.getUserId(),
                request.eventType(),
                request.eventText(),
                toJson(request.eventJson()),
                now
        ));
    }

    private void persistPendingToolContextFromConfirmation(AgentRun run, Object eventJson, LocalDateTime now) {
        JsonNode payload = objectMapper.valueToTree(eventJson == null ? Map.of() : eventJson);
        String toolCode = firstText(payload.path("toolCode"));
        if (toolCode.isBlank()) {
            return;
        }
        JsonNode arguments = payload.path("arguments");
        if (!arguments.isObject()) {
            arguments = objectMapper.createObjectNode();
        }
        AgentPendingToolContext ctx = new AgentPendingToolContext();
        ctx.setRunId(run.getId());
        ctx.setSessionId(run.getSessionId());
        ctx.setUserId(run.getUserId());
        ctx.setSelectedToolCode(toolCode);
        ctx.setCandidateToolCodesJson(toJson(List.of(toolCode)));
        ctx.setCollectedArgumentsJson(toJson(arguments));
        ctx.setMissingArgumentsJson(toJson(List.of()));
        ctx.setClarifyingQuestion(null);
        ctx.setConfirmationRequired(true);
        ctx.setSource("tool_confirmation_required");
        ctx.setStatus("ACTIVE");
        ctx.setCreatedAt(now);
        ctx.setUpdatedAt(now);
        agentPendingToolContextMapper.expireBySessionId(run.getSessionId());
        agentPendingToolContextMapper.insertPendingContext(ctx);
    }

    private void persistPendingToolContextFromIntentDetected(AgentRun run, Object eventJson, LocalDateTime now) {
        JsonNode payload = objectMapper.valueToTree(eventJson == null ? Map.of() : eventJson);
        if (!"needs_clarification".equalsIgnoreCase(firstText(payload.path("intent")))) {
            return;
        }
        String toolCode = firstText(payload.path("selectedToolCode"));
        if (toolCode.isBlank()) {
            return;
        }
        JsonNode missing = payload.path("missingFields");
        List<String> missingFields = new java.util.ArrayList<>();
        if (missing.isArray()) {
            missing.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    missingFields.add(node.asText());
                }
            });
        }
        AgentPendingToolContext ctx = new AgentPendingToolContext();
        ctx.setRunId(run.getId());
        ctx.setSessionId(run.getSessionId());
        ctx.setUserId(run.getUserId());
        ctx.setSelectedToolCode(toolCode);
        ctx.setCandidateToolCodesJson(toJson(List.of(toolCode)));
        ctx.setCollectedArgumentsJson(toJson(Map.of()));
        ctx.setMissingArgumentsJson(toJson(missingFields));
        ctx.setClarifyingQuestion(firstText(payload.path("clarifyingQuestion")));
        ctx.setConfirmationRequired(false);
        ctx.setSource("intent_needs_clarification");
        ctx.setStatus("ACTIVE");
        ctx.setCreatedAt(now);
        ctx.setUpdatedAt(now);
        agentPendingToolContextMapper.expireBySessionId(run.getSessionId());
        agentPendingToolContextMapper.insertPendingContext(ctx);
    }

    private void persistPendingToolContextFromMissingArguments(AgentRun run, Object eventJson, LocalDateTime now) {
        JsonNode payload = objectMapper.valueToTree(eventJson == null ? Map.of() : eventJson);
        String toolCode = firstText(payload.path("toolCode"));
        if (toolCode.isBlank()) {
            return;
        }
        JsonNode missing = payload.path("missingArguments");
        List<String> missingFields = new java.util.ArrayList<>();
        if (missing.isArray()) {
            missing.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    missingFields.add(node.asText());
                }
            });
        }
        AgentPendingToolContext ctx = new AgentPendingToolContext();
        ctx.setRunId(run.getId());
        ctx.setSessionId(run.getSessionId());
        ctx.setUserId(run.getUserId());
        ctx.setSelectedToolCode(toolCode);
        ctx.setCandidateToolCodesJson(toJson(List.of(toolCode)));
        ctx.setCollectedArgumentsJson(toJson(Map.of()));
        ctx.setMissingArgumentsJson(toJson(missingFields));
        ctx.setClarifyingQuestion(firstText(payload.path("toolName")));
        ctx.setConfirmationRequired(false);
        ctx.setSource("tool_missing_arguments");
        ctx.setStatus("ACTIVE");
        ctx.setCreatedAt(now);
        ctx.setUpdatedAt(now);
        agentPendingToolContextMapper.expireBySessionId(run.getSessionId());
        agentPendingToolContextMapper.insertPendingContext(ctx);
    }

    @Override
    @Transactional
    public AgentToolCallResponse createToolCall(Long runId, CreateAgentToolCallRequest request) {
        AgentRun run = findRun(runId);
        if (!"RUNNING".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_CANCELLABLE, "当前 Agent 运行不可创建工具调用");
        }
        agentToolDescriptorService.getToolForAgent(run.getUserId(), request.toolCode());
        AgentToolCall existing = agentToolCallMapper.selectLatestByRunIdAndToolCode(runId, request.toolCode());
        if (existing != null) {
            return AgentToolCallResponse.from(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        AgentToolCall call = new AgentToolCall();
        call.setRunId(runId);
        call.setUserId(run.getUserId());
        call.setToolCode(request.toolCode());
        call.setStatus("RUNNING");
        call.setArgumentsJson(toJsonOrEmpty(request.argumentsJson()));
        call.setStartedAt(now);
        call.setCreatedAt(now);
        agentToolCallMapper.insertToolCall(call);
        appendEventInternal(runId, run.getUserId(), "tool.started", "工具调用已开始", toolEventJson(call), now);
        return AgentToolCallResponse.from(call);
    }

    @Override
    @Transactional
    public AgentToolCallResponse bindToolCallTask(Long toolCallId, BindAgentToolCallTaskRequest request) {
        AgentToolCall call = findToolCall(toolCallId);
        if (call.getTaskId() != null) {
            if (Objects.equals(call.getTaskId(), request.taskId())) {
                return AgentToolCallResponse.from(call);
            }
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent tool call already bound to another task");
        }
        if (!"RUNNING".equals(call.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Only running Agent tool calls can bind task");
        }
        int updated = agentToolCallMapper.bindTaskId(toolCallId, request.taskId());
        if (updated == 0) {
            AgentToolCall current = findToolCall(toolCallId);
            if (Objects.equals(current.getTaskId(), request.taskId())) {
                return AgentToolCallResponse.from(current);
            }
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent tool call cannot bind task");
        }
        return AgentToolCallResponse.from(findToolCall(toolCallId));
    }

    @Override
    @Transactional
    public AgentToolCallResponse completeToolCall(Long toolCallId, CompleteAgentToolCallRequest request) {
        AgentToolCall call = findToolCall(toolCallId);
        if (TOOL_CALL_TERMINAL_STATUSES.contains(call.getStatus())) {
            return AgentToolCallResponse.from(call);
        }
        AgentRun run = findRun(call.getRunId());
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            agentToolCallMapper.markFailed(toolCallId, "RUN_ALREADY_TERMINATED", "Agent 运行已结束", LocalDateTime.now());
            return AgentToolCallResponse.from(findToolCall(toolCallId));
        }
        LocalDateTime now = LocalDateTime.now();
        agentToolCallMapper.markSuccess(toolCallId, toJson(request.resultJson()), now);
        appendEventInternal(
                call.getRunId(),
                call.getUserId(),
                "tool.finished",
                "工具调用已完成",
                toJson(toolFinishedEventJson(call, "SUCCESS", request.resultJson(), null, null)),
                now
        );
        agentMetrics.recordToolCallOutcome(call.getToolCode(), "SUCCESS", firstNonNull(call.getStartedAt(), call.getCreatedAt()), now);
        return AgentToolCallResponse.from(findToolCall(toolCallId));
    }

    @Override
    @Transactional
    public AgentToolCallResponse failToolCall(Long toolCallId, FailAgentToolCallRequest request) {
        AgentToolCall call = findToolCall(toolCallId);
        if (TOOL_CALL_TERMINAL_STATUSES.contains(call.getStatus())) {
            return AgentToolCallResponse.from(call);
        }
        LocalDateTime now = LocalDateTime.now();
        String errorMessage = errorMessagePreview(request.errorMessage());
        agentToolCallMapper.markFailed(toolCallId, request.errorCode(), errorMessage, now);
        appendEventInternal(
                call.getRunId(),
                call.getUserId(),
                "tool.finished",
                errorMessage,
                toJson(toolFinishedEventJson(call, "FAILED", null, request.errorCode(), errorMessage)),
                now
        );
        agentMetrics.recordToolCallOutcome(call.getToolCode(), "FAILED", firstNonNull(call.getStartedAt(), call.getCreatedAt()), now);
        return AgentToolCallResponse.from(findToolCall(toolCallId));
    }

    @Override
    @Transactional
    public AgentRunResponse upsertStreamingAnswer(Long runId, UpsertStreamingAgentAnswerRequest request) {
        AgentRun run = findRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return AgentRunResponse.from(run);
        }
        String contentText = request.contentText() == null ? "" : request.contentText().trim();
        if (contentText.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "流式正文不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentMessage assistant = agentMessageMapper.findActiveAssistantByRunId(runId);
        if (assistant == null) {
            assistant = new AgentMessage();
            assistant.setSessionId(run.getSessionId());
            assistant.setUserId(run.getUserId());
            assistant.setRole("ASSISTANT");
            assistant.setContentText(contentText);
            assistant.setRunId(runId);
            assistant.setParentMessageId(run.getSourceUserMessageId());
            assistant.setStatus("ACTIVE");
            assistant.setSupersededAt(null);
            assistant.setCreatedAt(now);
            agentMessageMapper.insertMessage(assistant);
        } else {
            agentMessageMapper.updateContentText(assistant.getId(), contentText, null);
        }
        agentSessionMapper.updateActiveLeaf(run.getSessionId(), assistant.getId(), now);
        appendEventInternal(
                runId,
                run.getUserId(),
                "message.completed",
                contentText,
                toJson(Map.of("content", contentText, "source", "streaming_answer_snapshot")),
                now
        );
        return AgentRunResponse.from(findRun(runId));
    }

    @Override
    @Transactional
    public AgentRunResponse updateConversationSummary(Long runId, String conversationSummary) {
        AgentRun run = findRun(runId);
        String summary = conversationSummary == null ? "" : conversationSummary.trim();
        if (summary.isEmpty()) {
            return AgentRunResponse.from(run);
        }
        if (summary.length() > 12000) {
            summary = summary.substring(0, 12000);
        }
        agentSessionMapper.updateConversationSummary(run.getSessionId(), summary, LocalDateTime.now());
        return AgentRunResponse.from(run);
    }

    @Override
    @Transactional
    public AgentRunResponse completeRun(Long runId, CompleteAgentRunRequest request) {
        AgentRun run = findRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return AgentRunResponse.from(run);
        }
        LocalDateTime now = LocalDateTime.now();
        AgentMessage assistant = agentMessageMapper.findActiveAssistantByRunId(runId);
        AgentModelConfig modelConfig = resolveModelConfigEntityForRun(run);
        int estimatedCredits = run.getEstimatedCredits() == null ? 0 : Math.max(0, run.getEstimatedCredits());
        int consumedCredits = resolveConsumedCredits(request.consumedCredits(), request.promptTokens(), request.completionTokens(), modelConfig, estimatedCredits);
        if (agentRunMapper.markSuccess(runId, request.intent(), request.modelProviderCode(), request.modelName(), consumedCredits, now) == 0) {
            return AgentRunResponse.from(findRun(runId));
        }
        if (assistant == null) {
            assistant = new AgentMessage();
            assistant.setSessionId(run.getSessionId());
            assistant.setUserId(run.getUserId());
            assistant.setRole("ASSISTANT");
            assistant.setContentText(request.finalAnswer());
            assistant.setRunId(runId);
            assistant.setParentMessageId(run.getSourceUserMessageId());
            assistant.setStatus("ACTIVE");
            assistant.setSupersededAt(null);
            assistant.setCreatedAt(now);
            agentMessageMapper.insertMessage(assistant);
        } else {
            agentMessageMapper.updateContentText(assistant.getId(), request.finalAnswer(), null);
        }
        creditService.settle(run.getUserId(), CreditSourceType.AGENT_RUN, runId, consumedCredits);
        creditService.release(run.getUserId(), CreditSourceType.AGENT_RUN, runId, estimatedCredits - consumedCredits);
        PricingQuote completedQuote = pricingService.computeTokenQuote(modelConfig, request.promptTokens(), request.completionTokens());
        billingService.recordUsage("AGENT_RUN", runId, run.getUserId(), modelConfig,
                request.promptTokens(), request.completionTokens(), null, consumedCredits,
                completedQuote.vendorCost(), completedQuote.markupRatio());
        appendEventInternal(runId, run.getUserId(), "run.completed", "Agent 运行已完成", null, now);
        agentSessionMapper.updateActiveLeaf(run.getSessionId(), assistant.getId(), now);
        agentRateLimitService.decrementActiveRun(run.getUserId(), runId);
        agentPendingToolContextMapper.expireByRunId(runId);
        agentMetrics.recordRunOutcome("SUCCESS", request.intent(), firstNonNull(run.getStartedAt(), run.getCreatedAt()), now);
        cleanupEventStreams(runId);
        return AgentRunResponse.from(findRun(runId));
    }

    private void failOpenToolCalls(Long runId, Long userId, String errorCode, String errorMessage, LocalDateTime now) {
        String limitedErrorMessage = errorMessagePreview(errorMessage);
        agentToolCallMapper.findByRunId(runId)
                .stream()
                .filter(call -> !TOOL_CALL_TERMINAL_STATUSES.contains(call.getStatus()))
                .forEach(call -> {
                    agentToolCallMapper.markFailed(call.getId(), errorCode, limitedErrorMessage, now);
                    appendEventInternal(
                            runId,
                            userId,
                            "tool.finished",
                            limitedErrorMessage,
                            toJson(toolFinishedEventJson(call, "FAILED", null, errorCode, limitedErrorMessage)),
                            now
                    );
                    agentMetrics.recordToolCallOutcome(call.getToolCode(), "FAILED", firstNonNull(call.getStartedAt(), call.getCreatedAt()), now);
                });
    }

    private int resolveConsumedCredits(Integer reportedCredits,
                                       Integer promptTokens,
                                       Integer completionTokens,
                                       AgentModelConfig modelConfig,
                                       int estimatedCredits) {
        int reported = reportedCredits == null ? 0 : Math.max(0, reportedCredits);
        int priced = tokenPricedCredits(promptTokens, completionTokens, modelConfig);
        int resolved = Math.max(reported, priced);
        return Math.max(0, Math.min(resolved, Math.max(0, estimatedCredits)));
    }

    private int tokenPricedCredits(Integer promptTokens, Integer completionTokens, AgentModelConfig modelConfig) {
        if (modelConfig == null) {
            return 0;
        }
        return pricingService.computeTokenQuote(modelConfig, promptTokens, completionTokens).chargeCredits();
    }

    @Override
    @Transactional
    public AgentRunResponse failRun(Long runId, FailAgentRunRequest request) {
        AgentRun run = findRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return AgentRunResponse.from(run);
        }
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig modelConfig = resolveModelConfigEntityForRun(run);
        int estimatedCredits = run.getEstimatedCredits() == null ? 0 : Math.max(0, run.getEstimatedCredits());
        int consumedCredits = resolveConsumedCredits(request.consumedCredits(), request.promptTokens(), request.completionTokens(), modelConfig, estimatedCredits);
        String errorMessage = errorMessagePreview(request.errorMessage());
        if (agentRunMapper.markFailedWithConsumedCredits(runId, request.errorCode(), errorMessage, consumedCredits, now) == 0) {
            return AgentRunResponse.from(findRun(runId));
        }
        failOpenToolCalls(runId, run.getUserId(), request.errorCode(), errorMessage, now);
        if (consumedCredits > 0) {
            creditService.settle(run.getUserId(), CreditSourceType.AGENT_RUN, runId, consumedCredits);
        }
        creditService.release(run.getUserId(), CreditSourceType.AGENT_RUN, runId, estimatedCredits - consumedCredits);
        PricingQuote failedQuote = pricingService.computeTokenQuote(modelConfig, request.promptTokens(), request.completionTokens());
        billingService.recordUsage("AGENT_RUN", runId, run.getUserId(), modelConfig,
                request.promptTokens(), request.completionTokens(), null, consumedCredits,
                failedQuote.vendorCost(), failedQuote.markupRatio());
        appendEventInternal(
                runId,
                run.getUserId(),
                "run.failed",
                errorMessage,
                toJson(new FailAgentRunRequest(
                        request.errorCode(),
                        errorMessage,
                        request.consumedCredits(),
                        request.promptTokens(),
                        request.completionTokens()
                )),
                now
        );
        agentRateLimitService.decrementActiveRun(run.getUserId(), runId);
        agentPendingToolContextMapper.expireByRunId(runId);
        agentMetrics.recordRunOutcome("FAILED", run.getIntent(), firstNonNull(run.getStartedAt(), run.getCreatedAt()), now);
        cleanupEventStreams(runId);
        return AgentRunResponse.from(findRun(runId));
    }

    @Override
    @Transactional
    public void saveGraphCheckpoint(Long runId, String checkpointJson) {
        findRun(runId);
        agentRunMapper.updateGraphCheckpoint(runId, checkpointJson, LocalDateTime.now());
    }

    @Override
    public String getGraphCheckpoint(Long runId) {
        findRun(runId);
        return agentRunMapper.selectGraphCheckpoint(runId);
    }

    @Override
    @Transactional
    public void clearGraphCheckpoint(Long runId) {
        findRun(runId);
        agentRunMapper.updateGraphCheckpoint(runId, null, LocalDateTime.now());
    }

    private CreateAgentMessageResponse executeStartRun(Long userId,
                                                       AgentSession session,
                                                       AgentMessage userMessage,
                                                       Long parentRunId,
                                                       String clientRequestId,
                                                       String sessionTitleContentHint,
                                                       LocalDateTime now,
                                                       List<Long> fileIds,
                                                       Long requestedModelConfigId,
                                                       String preferredToolCode) {
        Long sessionId = session.getId();
        int creditBudget = Math.max(0, appProperties.getAgent().getDefaultCreditBudget());

        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("CREATED");
        run.setEstimatedCredits(creditBudget);
        run.setConsumedCredits(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setParentRunId(parentRunId);
        run.setSourceUserMessageId(userMessage.getId());
        run.setClientRequestId(clientRequestId);
        run.setPreferredToolCode(preferredToolCode);
        agentRunMapper.insertRun(run);

        userMessage.setRunId(run.getId());
        agentMessageMapper.updateById(userMessage);
        agentSessionMapper.updateActiveLeaf(sessionId, userMessage.getId(), now);

        if (parentRunId != null) {
            agentFileMapper.reattachFilesFromRun(userId, sessionId, parentRunId, run.getId(), now);
        } else {
            agentFileService.attachPendingFilesToRun(userId, sessionId, run.getId(), fileIds);
        }
        persistUserMessageAttachments(userMessage, userId, sessionId, run.getId());

        ModelConnectivityCheck connectivity = checkModelConnectivity(requestedModelConfigId);
        run.setModelConfigId(connectivity.config().id());
        run.setModelProviderCode(connectivity.config().provider());
        run.setModelName(connectivity.config().modelName());
        agentRunMapper.updateModel(
                run.getId(),
                connectivity.config().id(),
                connectivity.config().provider(),
                connectivity.config().modelName(),
                now
        );
        if (!connectivity.success()) {
            String errorMessage = errorMessagePreview(messageOrDefault(connectivity.message(), "Agent model connectivity check failed"));
            agentRunMapper.markFailed(run.getId(), "MODEL_CALL_FAILED", errorMessage, now);
            appendEventInternal(
                    run.getId(),
                    userId,
                    "model.preflight_failed",
                    errorMessage,
                    toJson(Map.of(
                            "provider", connectivity.config().provider(),
                            "modelName", connectivity.config().modelName(),
                            "message", errorMessage
                    )),
                    now
            );
            agentSessionMapper.touch(sessionId, now);
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, errorMessage);
        }

        AgentContextSnapshot snapshot = createContextSnapshot(run, session, userMessage, connectivity.config(), now);
        run.setContextSnapshotId(snapshot.getId());
        agentRunMapper.updateContextSnapshot(run.getId(), snapshot.getId(), now);

        creditService.freeze(userId, CreditSourceType.AGENT_RUN, run.getId(), creditBudget);
        agentRunMapper.markRunning(run.getId(), now);
        agentRateLimitService.incrementActiveRun(userId, run.getId());
        appendEventInternal(run.getId(), userId, "run.started", "Agent 已开始处理", null, now);
        agentSessionMapper.touch(sessionId, now);
        if (sessionTitleContentHint != null && !sessionTitleContentHint.isBlank()) {
            autoUpdateSessionTitle(session, sessionTitleContentHint, now);
        }

        Long executeRunId = run.getId();
        runAfterCommit(() -> notifyAgentService(executeRunId, () -> agentServiceClient.executeRun(executeRunId)));
        return new CreateAgentMessageResponse(sessionId, userMessage.getId(), run.getId(), "RUNNING");
    }

    private void persistUserMessageAttachments(AgentMessage userMessage, Long userId, Long sessionId, Long runId) {
        List<AgentFile> attachedFiles = agentFileMapper.findByRun(userId, sessionId, runId, FILE_CONTEXT_LIMIT);
        List<Map<String, Object>> urlAttachments = urlAttachmentItems(userMessage == null ? null : userMessage.getContentJson());
        if (attachedFiles.isEmpty() && urlAttachments.isEmpty()) {
            return;
        }
        List<Map<String, Object>> attachments = new java.util.ArrayList<>(urlAttachments);
        int attachmentIndex = attachments.size();
        for (AgentFile file : attachedFiles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", file.getId());
            item.put("name", referenceAttachmentLabel(attachmentIndex, file.getOriginalFilename(), file.getContentType()));
            item.put("contentType", file.getContentType());
            item.put("size", file.getFileSize());
            item.put("status", file.getStatus());
            item.put("url", "/api/v1/agent/sessions/" + sessionId + "/files/" + file.getId() + "/content");
            item.put("source", "agent_file");
            attachments.add(item);
            attachmentIndex++;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attachments", attachments);
        JsonNode existing = parseJsonNode(userMessage.getContentJson());
        if (existing.path("agentOptions").isObject()) {
            payload.put("agentOptions", objectMapper.convertValue(existing.path("agentOptions"), Map.class));
        }
        if (existing.path("referenceMentions").isArray()) {
            payload.put("referenceMentions", objectMapper.convertValue(existing.path("referenceMentions"), List.class));
        }
        if (existing.path("globalFileIds").isArray()) {
            payload.put("globalFileIds", objectMapper.convertValue(existing.path("globalFileIds"), List.class));
        }
        if (existing.path("contentParts").isArray()) {
            payload.put("contentParts", objectMapper.convertValue(existing.path("contentParts"), List.class));
        }
        if (existing.path("positionalPrompt").isTextual()) {
            payload.put("positionalPrompt", existing.path("positionalPrompt").asText());
        }
        userMessage.setContentJson(toJson(payload));
        agentMessageMapper.updateById(userMessage);
    }

    private String messageContentJson(
            List<Map<String, Object>> rawItems,
            String intelligenceLevel,
            List<Map<String, Object>> referenceMentions,
            List<Object> globalFileIds,
            List<Map<String, Object>> contentParts,
            String positionalPrompt
    ) {
        List<Map<String, Object>> items = normalizedUrlAttachments(rawItems);
        String normalizedLevel = normalizeIntelligenceLevel(intelligenceLevel);
        List<Map<String, Object>> mentions = referenceMentions == null ? List.of() : referenceMentions.stream()
                .filter(item -> item != null && item.get("url") instanceof String url && !url.isBlank())
                .toList();
        List<Object> globalIds = globalFileIds == null ? List.of() : globalFileIds.stream()
                .filter(item -> item instanceof String || item instanceof Number)
                .toList();
        List<Map<String, Object>> parts = contentParts == null ? List.of() : contentParts.stream()
                .filter(item -> item != null && item.get("type") instanceof String)
                .toList();
        String prompt = positionalPrompt == null ? "" : positionalPrompt.trim();
        if (items.isEmpty() && normalizedLevel == null && mentions.isEmpty() && globalIds.isEmpty() && parts.isEmpty() && prompt.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!items.isEmpty()) {
            payload.put("attachments", items);
        }
        if (!mentions.isEmpty()) {
            payload.put("referenceMentions", mentions);
        }
        if (!globalIds.isEmpty()) {
            payload.put("globalFileIds", globalIds);
        }
        if (!parts.isEmpty()) {
            payload.put("contentParts", parts);
        }
        if (!prompt.isEmpty()) {
            payload.put("positionalPrompt", prompt);
        }
        if (normalizedLevel != null) {
            payload.put("agentOptions", Map.of("intelligenceLevel", normalizedLevel));
        }
        return toJson(payload);
    }

    private String editedMessageContentJson(String existingJson, String editedText) {
        JsonNode existing = parseJsonNode(existingJson);
        if (!existing.isObject()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        copyJsonField(existing, payload, "attachments", List.class);
        copyJsonField(existing, payload, "referenceMentions", List.class);
        copyJsonField(existing, payload, "globalFileIds", List.class);
        copyJsonField(existing, payload, "agentOptions", Map.class);
        List<Map<String, Object>> rewrittenParts = rewrittenContentPartsForEdit(existing.path("contentParts"), editedText);
        if (!rewrittenParts.isEmpty()) {
            payload.put("contentParts", rewrittenParts);
        }
        if (!editedText.isBlank() && (
                existing.path("positionalPrompt").isTextual()
                        || existing.path("contentParts").isArray()
                        || existing.path("referenceMentions").isArray()
                        || existing.path("attachments").isArray()
        )) {
            payload.put("positionalPrompt", editedText);
        }
        return payload.isEmpty() ? null : toJson(payload);
    }

    private void copyJsonField(JsonNode existing, Map<String, Object> payload, String fieldName, Class<?> targetType) {
        JsonNode value = existing.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        if (targetType == List.class && !value.isArray()) {
            return;
        }
        if (targetType == Map.class && !value.isObject()) {
            return;
        }
        payload.put(fieldName, objectMapper.convertValue(value, targetType));
    }

    private List<Map<String, Object>> rewrittenContentPartsForEdit(JsonNode contentParts, String editedText) {
        if (!contentParts.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> rewritten = new java.util.ArrayList<>();
        boolean textInserted = false;
        for (JsonNode item : contentParts) {
            if (!item.isObject() || !item.path("type").isTextual()) {
                continue;
            }
            Map<String, Object> part = objectMapper.convertValue(item, Map.class);
            String type = item.path("type").asText("").trim().toLowerCase();
            if ("text".equals(type)) {
                if (!textInserted && !editedText.isBlank()) {
                    part.put("text", editedText);
                    rewritten.add(part);
                    textInserted = true;
                }
                continue;
            }
            rewritten.add(part);
        }
        if (!textInserted && !editedText.isBlank()) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", editedText);
            rewritten.add(textPart);
        }
        return rewritten;
    }

    private String normalizeIntelligenceLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if ("high".equals(normalized)) {
            return "high";
        }
        if ("standard".equals(normalized) || "normal".equals(normalized)) {
            return "standard";
        }
        return null;
    }

    private String intelligenceLevelFromMessage(AgentMessage userMessage) {
        JsonNode root = parseJsonNode(userMessage == null ? null : userMessage.getContentJson());
        return normalizeIntelligenceLevel(firstText(root.path("agentOptions").path("intelligenceLevel")));
    }

    private List<InternalReferenceMentionResponse> referenceMentionContexts(AgentMessage userMessage) {
        JsonNode root = parseJsonNode(userMessage == null ? null : userMessage.getContentJson());
        JsonNode mentions = root.path("referenceMentions");
        if (!mentions.isArray()) {
            return List.of();
        }
        List<InternalReferenceMentionResponse> contexts = new java.util.ArrayList<>();
        for (JsonNode item : mentions) {
            String url = firstText(item.path("url"));
            if (url.isBlank()) {
                continue;
            }
            contexts.add(new InternalReferenceMentionResponse(
                    firstText(item.path("token")),
                    firstText(item.path("refLabel")),
                    firstText(item.path("assetKey")),
                    url,
                    firstText(item.path("kind")),
                    firstText(item.path("source"))
            ));
        }
        return contexts;
    }

    private List<Object> globalFileIdsFromMessage(AgentMessage userMessage) {
        JsonNode root = parseJsonNode(userMessage == null ? null : userMessage.getContentJson());
        JsonNode ids = root.path("globalFileIds");
        if (!ids.isArray()) {
            return List.of();
        }
        List<Object> values = new java.util.ArrayList<>();
        for (JsonNode item : ids) {
            if (item.isNumber()) {
                values.add(item.isIntegralNumber() ? item.asLong() : item.asDouble());
            } else if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private List<Map<String, Object>> contentPartsFromMessage(AgentMessage userMessage) {
        JsonNode root = parseJsonNode(userMessage == null ? null : userMessage.getContentJson());
        JsonNode parts = root.path("contentParts");
        if (!parts.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> values = new java.util.ArrayList<>();
        for (JsonNode item : parts) {
            if (!item.isObject() || !item.path("type").isTextual()) {
                continue;
            }
            values.add(objectMapper.convertValue(item, Map.class));
        }
        return values;
    }

    private String positionalPromptFromMessage(AgentMessage userMessage) {
        JsonNode root = parseJsonNode(userMessage == null ? null : userMessage.getContentJson());
        return firstText(root.path("positionalPrompt"));
    }

    private List<Map<String, Object>> urlAttachmentItems(String contentJson) {
        JsonNode root = parseJsonNode(contentJson);
        JsonNode attachments = root.path("attachments");
        if (!attachments.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        int index = 0;
        for (JsonNode item : attachments) {
            String source = firstText(item.path("source"));
            String url = firstText(item.path("url"), item.path("downloadUrl"));
            if (!isUrlAttachmentSource(source) || !isAllowedMaterialUrl(url)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", firstText(item.path("id")).isBlank() ? "url-" + index : firstText(item.path("id")));
            normalized.put("name", nonBlankOrDefault(firstText(item.path("name"), item.path("title")), "素材附件"));
            normalized.put("contentType", firstText(item.path("contentType"), item.path("type")));
            normalized.put("size", item.path("size").isNumber() ? item.path("size").asLong() : 0);
            normalized.put("status", "READY");
            normalized.put("url", url);
            normalized.put("source", normalizedAttachmentSource(source));
            items.add(normalized);
            index++;
            if (items.size() >= FILE_CONTEXT_LIMIT) {
                break;
            }
        }
        return items;
    }

    private List<InternalAgentFileContextResponse> urlAttachmentContexts(AgentMessage userMessage) {
        List<Map<String, Object>> items = urlAttachmentItems(userMessage == null ? null : userMessage.getContentJson());
        List<InternalAgentFileContextResponse> contexts = new java.util.ArrayList<>();
        long syntheticId = -1L;
        for (Map<String, Object> item : items) {
            if ("agent_file".equalsIgnoreCase(stringValue(item.get("source")))) {
                continue;
            }
            contexts.add(InternalAgentFileContextResponse.urlAttachment(
                    syntheticId--,
                    nonBlankOrDefault(stringValue(item.get("name")), "素材附件"),
                    stringValue(item.get("contentType")),
                    stringValue(item.get("url"))
            ));
        }
        return contexts;
    }

    private String normalizedAttachmentSource(String source) {
        String normalized = source == null ? "" : source.trim().toLowerCase();
        if ("chat_reference".equals(normalized) || "agent_file".equals(normalized)) {
            return normalized;
        }
        return "url";
    }

    private List<Map<String, Object>> normalizedUrlAttachments(List<Map<String, Object>> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        int index = 0;
        for (Map<String, Object> raw : rawItems) {
            if (raw == null) {
                continue;
            }
            String url = stringValue(raw.get("url"));
            if (!isAllowedMaterialUrl(url)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", nonBlankOrDefault(stringValue(raw.get("id")), "url-" + index));
            item.put("name", nonBlankOrDefault(stringValue(raw.get("name")), nonBlankOrDefault(stringValue(raw.get("title")), "素材附件")));
            item.put("contentType", stringValue(raw.get("contentType")));
            item.put("size", raw.get("size") instanceof Number number ? number.longValue() : 0L);
            item.put("status", "READY");
            item.put("url", url);
            String source = nonBlankOrDefault(stringValue(raw.get("source")), "url");
            item.put("source", normalizedAttachmentSource(source));
            normalized.add(item);
            index++;
            if (normalized.size() >= FILE_CONTEXT_LIMIT) {
                break;
            }
        }
        return normalized;
    }

    private boolean isUrlAttachmentSource(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.trim().toLowerCase();
        return "url".equals(normalized) || "chat_reference".equals(normalized) || "agent_file".equals(normalized);
    }

    private boolean isAllowedMaterialUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("/generated/")) {
            return true;
        }
        if (trimmed.startsWith("/api/v1/agent/sessions/")) {
            return true;
        }
        String workerBase = appProperties.getAgent().getWorkerMediaBaseUrl();
        if (workerBase != null && !workerBase.isBlank() && trimmed.startsWith(workerBase.replaceAll("/+$", "") + "/generated/")) {
            return true;
        }
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private AgentContextSnapshot createContextSnapshot(AgentRun run,
                                                       AgentSession session,
                                                       AgentMessage userMessage,
                                                       InternalAgentModelConfigResponse modelConfig,
                                                       LocalDateTime now) {
        Long anchorId = run.getSourceUserMessageId() == null ? userMessage.getId() : run.getSourceUserMessageId();
        int maxHistoryMessages = contextHistoryLimit();
        List<AgentMessage> contextHistory = activePathBeforeUserMessage(run.getSessionId(), anchorId, maxHistoryMessages);
        List<AgentFile> readyFiles = agentFileMapper.findReadyByRun(
                run.getUserId(),
                run.getSessionId(),
                run.getId(),
                FILE_CONTEXT_LIMIT
        );
        Map<Long, String> filenames = readyFiles.stream()
                .collect(java.util.stream.Collectors.toMap(AgentFile::getId, AgentFile::getOriginalFilename));
        List<InternalAgentFileChunkContextResponse> fileChunks = retrieveRelevantFileChunks(
                run,
                userMessage == null ? "" : userMessage.getContentText(),
                filenames
        );
        int estimatedTokens = estimateTokens(userMessage == null ? "" : userMessage.getContentText());
        for (AgentMessage message : contextHistory) {
            estimatedTokens += estimateTokens(message.getContentText());
        }
        for (InternalAgentFileChunkContextResponse chunk : fileChunks) {
            estimatedTokens += estimateTokens(chunk.contentText());
        }
        List<InternalReferenceMentionResponse> referenceMentions = referenceMentionContexts(userMessage);
        List<Map<String, Object>> contentParts = contentPartsFromMessage(userMessage);
        String positionalPrompt = positionalPromptFromMessage(userMessage);
        boolean visionInputEnabled = hasCapability(modelConfig.capabilities(), "VISION_INPUT");
        List<String> visionInputUrlSummary = referenceMentions.stream()
                .map(InternalReferenceMentionResponse::url)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .limit(10)
                .toList();
        List<AgentToolDescriptorResponse> visibleTools = agentToolDescriptorService.listAvailableToolsForUser(run.getUserId());
        var availableSkills = agentSkillBundleService.listAvailableSkillDescriptors(visibleTools);
        var toolPreferences = agentToolPreferenceMapper.findByUserId(run.getUserId())
                .stream()
                .map(com.aiminilab.aitoolmarket.agent.dto.AgentToolPreferenceResponse::from)
                .toList();
        Map<String, String> agentSettings = systemSettingService.settings().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().startsWith("agent."))
                .sorted(Map.Entry.comparingByKey())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null ? "" : entry.getValue(),
                        (left, right) -> right,
                        java.util.LinkedHashMap::new
                ));
        List<String> truncationReasons = new java.util.ArrayList<>();
        if (contextHistory.size() >= maxHistoryMessages) truncationReasons.add("history_message_limit_reached");
        if (readyFiles.size() >= FILE_CONTEXT_LIMIT) truncationReasons.add("file_context_limit_reached");
        if (fileChunks.size() >= FILE_CHUNK_CONTEXT_LIMIT) truncationReasons.add("file_chunk_limit_reached");

        var snapshotPayload = new java.util.LinkedHashMap<String, Object>();
        snapshotPayload.put("version", 2);
        snapshotPayload.put("strategy", "recent_history_plus_run_files");
        snapshotPayload.put("historySource", "message_tree");
        snapshotPayload.put("runId", run.getId());
        snapshotPayload.put("sessionId", run.getSessionId());
        snapshotPayload.put("leafMessageId", anchorId);
        snapshotPayload.put("workspaceId", session.getWorkspaceId());
        snapshotPayload.put("sourceUserMessageId", run.getSourceUserMessageId());
        snapshotPayload.put("userMessage", userMessage == null || userMessage.getContentText() == null ? "" : userMessage.getContentText());
        snapshotPayload.put("modelConfig", Map.of(
                "id", modelConfig.id() == null ? 0 : modelConfig.id(),
                "provider", modelConfig.provider(),
                "modelName", modelConfig.modelName(),
                "enabled", modelConfig.enabled(),
                "capabilities", modelConfig.capabilities() == null ? List.of() : modelConfig.capabilities()
        ));
        snapshotPayload.put("configurationVersion", agentAuditRedactor.sha256(toJson(agentSettings)));
        snapshotPayload.put("runtimeSettings", agentSettings);
        snapshotPayload.put("visibleTools", visibleTools);
        snapshotPayload.put("availableSkills", availableSkills);
        snapshotPayload.put("toolPreferences", toolPreferences);
        snapshotPayload.put("preferredToolCode", run.getPreferredToolCode() == null ? "" : run.getPreferredToolCode());
        snapshotPayload.put("memoryHits", List.of());
        snapshotPayload.put("memoryCollectionStage", "agent_service_model_request");
        snapshotPayload.put("truncationReasons", truncationReasons);
        snapshotPayload.put("referenceMentions", referenceMentions);
        snapshotPayload.put("contentParts", contentParts);
        snapshotPayload.put("positionalPrompt", positionalPrompt == null ? "" : positionalPrompt);
        snapshotPayload.put("visionInputEnabled", visionInputEnabled);
        snapshotPayload.put("visionInputImageCount", visionInputUrlSummary.size());
        snapshotPayload.put("visionInputUrlSummary", visionInputUrlSummary);
        snapshotPayload.put("limits", Map.of(
                "maxHistoryMessages", maxHistoryMessages,
                "fileContextLimit", FILE_CONTEXT_LIMIT,
                "fileChunkContextLimit", FILE_CHUNK_CONTEXT_LIMIT
        ));
        snapshotPayload.put("ancestorMessageIds", contextHistory.stream()
                .map(AgentMessage::getId)
                .toList());
        snapshotPayload.put("includedHistory", contextHistory.stream()
                .map(message -> Map.of(
                        "id", message.getId(),
                        "role", message.getRole(),
                        "chars", message.getContentText() == null ? 0 : message.getContentText().length(),
                        "content", message.getContentText() == null ? "" : message.getContentText(),
                        "preview", preview(message.getContentText(), 120)
                ))
                .toList());
        snapshotPayload.put("includedFiles", readyFiles.stream()
                .map(file -> Map.of(
                        "id", file.getId(),
                        "filename", file.getOriginalFilename(),
                        "contentType", file.getContentType() == null ? "" : file.getContentType(),
                        "status", file.getStatus()
                ))
                .toList());
        snapshotPayload.put("includedFileChunks", fileChunks.stream()
                .map(chunk -> Map.of(
                        "id", chunk.id(),
                        "fileId", chunk.fileId(),
                        "filename", chunk.originalFilename(),
                        "chunkIndex", chunk.chunkIndex(),
                        "contentText", chunk.contentText(),
                        "metadataJson", chunk.metadataJson() == null ? "" : chunk.metadataJson(),
                        "score", chunk.score()
                ))
                .toList());

        AgentContextSnapshot snapshot = new AgentContextSnapshot();
        snapshot.setRunId(run.getId());
        snapshot.setSessionId(run.getSessionId());
        snapshot.setUserId(run.getUserId());
        snapshot.setWorkspaceId(session.getWorkspaceId());
        snapshot.setModelConfigId(modelConfig.id());
        snapshot.setModelProviderCode(modelConfig.provider());
        snapshot.setModelName(modelConfig.modelName());
        snapshot.setStrategy("recent_history_plus_run_files");
        snapshot.setMaxHistoryMessages(maxHistoryMessages);
        snapshot.setHistoryMessageCount(contextHistory.size());
        snapshot.setFileCount(readyFiles.size());
        snapshot.setFileChunkCount(fileChunks.size());
        snapshot.setMemoryItemCount(0);
        snapshot.setEstimatedInputTokens(estimatedTokens);
        String rawSnapshot = toJson(snapshotPayload);
        snapshot.setPayloadSha256(agentAuditRedactor.sha256(rawSnapshot));
        snapshot.setSnapshotJson(toJson(agentAuditRedactor.redact(objectMapper.valueToTree(snapshotPayload))));
        snapshot.setCreatedAt(now);
        agentContextSnapshotMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private int contextHistoryLimit() {
        return parseIntSetting(
                systemSettingService.settings().get(AgentRuntimeSettings.MAX_HISTORY_MESSAGES_KEY),
                Math.max(1, appProperties.getAgent().getMaxHistoryMessages()),
                1,
                100
        );
    }

    private Long resolveParentMessageId(Long userId, AgentSession session, Long requestedParentMessageId) {
        Long parentMessageId = requestedParentMessageId == null ? session.getActiveLeafMessageId() : requestedParentMessageId;
        if (parentMessageId == null) {
            AgentMessage latest = agentMessageMapper.findLatestActiveBySession(session.getId());
            parentMessageId = latest == null ? null : latest.getId();
        }
        if (parentMessageId == null) {
            return null;
        }
        AgentMessage parent = agentMessageMapper.findByIdSessionAndUser(parentMessageId, session.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "父级消息不存在"));
        if (!"ACTIVE".equals(parent.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "父级消息不存在或已失效");
        }
        return parent.getId();
    }

    private List<AgentMessage> activePathBeforeUserMessage(Long sessionId, Long userMessageId, int limit) {
        if (userMessageId == null) {
            return List.of();
        }
        AgentMessage userMessage = agentMessageMapper.selectById(userMessageId);
        if (userMessage == null || userMessage.getParentMessageId() == null) {
            return List.of();
        }
        List<AgentMessage> path = agentMessageMapper.findActivePathByLeaf(sessionId, userMessage.getParentMessageId());
        if (path.isEmpty()) {
            return List.of();
        }
        int boundedLimit = Math.max(0, limit);
        return path.stream()
                .skip(Math.max(0, path.size() - boundedLimit))
                .toList();
    }

    private InternalAgentModelConfigResponse resolveModelConfigForRun(AgentRun run) {
        AgentModelConfig config = resolveModelConfigEntityForRun(run);
        if (config != null) {
            return InternalAgentModelConfigResponse.from(agentModelConfigService.resolveForExecution(config));
        }
        return agentModelConfigService.internalGet();
    }

    private Optional<String> recentToolResultContext(AgentRun run, List<Long> branchRunIds) {
        List<AgentToolCall> calls = recentSuccessfulToolCallsForBranch(run, branchRunIds, RECENT_TOOL_RESULT_CONTEXT_LIMIT);
        if (calls.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = new java.util.ArrayList<>();
        for (AgentToolCall call : calls) {
            lines.add(formatToolResultMemoryLine(call));
        }
        return Optional.of(
                "近期工具结果摘要（短期记忆；用户追问刚刚用了什么、结果在哪、能否继续修改时优先使用；不要因此再次调用工具）：\n"
                        + String.join("\n", lines)
        );
    }

    private List<InternalRecentToolCallContextResponse> recentToolCallContext(AgentRun run, List<Long> branchRunIds) {
        return recentSuccessfulToolCallsForBranch(run, branchRunIds, RECENT_TOOL_RESULT_CONTEXT_LIMIT)
                .stream()
                .map(call -> {
                    JsonNode result = parseJsonNode(call.getResultJson());
                    String resourceType = firstText(result.at("/data/resourceType"), result.path("resourceType"));
                    String contentText = firstText(
                            result.at("/data/contentText"),
                            result.path("contentText"),
                            result.path("resultSummary"),
                            result.path("summary")
                    );
                    String mediaUrls = extractMediaUrls(contentText);
                    if (mediaUrls.isBlank()) {
                        mediaUrls = extractMediaUrls(call.getResultJson());
                    }
                    return new InternalRecentToolCallContextResponse(
                            call.getId(),
                            call.getRunId(),
                            call.getToolCode(),
                            call.getTaskId(),
                            parseJsonMap(call.getArgumentsJson()),
                            parseJsonMap(call.getResultJson()),
                            resourceType,
                            splitMediaUrls(mediaUrls),
                            call.getCreatedAt()
                    );
                })
                .toList();
    }

    private List<AgentToolCall> recentSuccessfulToolCallsForBranch(AgentRun run, List<Long> branchRunIds, int limit) {
        if (branchRunIds == null || branchRunIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        return agentToolCallMapper.findRecentSuccessfulByRunIds(
                run.getUserId(),
                run.getSessionId(),
                branchRunIds,
                limit
        );
    }

    private List<Long> branchRunIdsForRun(AgentRun run, AgentMessage userMessage, Long anchorId) {
        LinkedHashSet<Long> runIds = new LinkedHashSet<>();
        if (anchorId != null) {
            activePathBeforeUserMessage(run.getSessionId(), anchorId, Integer.MAX_VALUE)
                    .stream()
                    .map(AgentMessage::getRunId)
                    .filter(Objects::nonNull)
                    .forEach(runIds::add);
        }
        if (userMessage != null && userMessage.getRunId() != null) {
            runIds.add(userMessage.getRunId());
        }
        runIds.add(run.getId());
        return new java.util.ArrayList<>(runIds);
    }

    private String formatToolResultMemoryLine(AgentToolCall call) {
        JsonNode result = parseJsonNode(call.getResultJson());
        String resourceType = firstText(result.at("/data/resourceType"), result.path("resourceType"));
        String contentText = firstText(
                result.at("/data/contentText"),
                result.path("contentText"),
                result.path("resultSummary"),
                result.path("summary")
        );
        String mediaUrls = extractMediaUrls(contentText);
        StringBuilder line = new StringBuilder("- ");
        line.append("toolCode=").append(nonBlankOrDefault(call.getToolCode(), "unknown"));
        if (call.getTaskId() != null) {
            line.append("; taskId=").append(call.getTaskId());
        }
        line.append("; status=").append(nonBlankOrDefault(call.getStatus(), "UNKNOWN"));
        if (resourceType != null && !resourceType.isBlank()) {
            line.append("; resourceType=").append(resourceType);
        }
        if (mediaUrls != null && !mediaUrls.isBlank()) {
            line.append("; mediaUrls=").append(mediaUrls);
        }
        if (contentText != null && !contentText.isBlank()) {
            line.append("; resultPreview=").append(toolResultContextPreview(contentText));
        }
        return line.toString();
    }

    private String toolResultContextPreview(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return "";
        }
        String sanitized = stripInlineMediaPayloads(contentText);
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        if (sanitized.length() <= MAX_TOOL_RESULT_CONTEXT_PREVIEW_LENGTH) {
            return sanitized;
        }
        int contentLength = Math.max(0, MAX_TOOL_RESULT_CONTEXT_PREVIEW_LENGTH - EVENT_TEXT_TRUNCATED_SUFFIX.length());
        return sanitized.substring(0, contentLength) + EVENT_TEXT_TRUNCATED_SUFFIX;
    }

    private String safeContextMessageText(String contentText, int maxLength) {
        if (contentText == null || contentText.isBlank()) {
            return contentText;
        }
        String sanitized = stripInlineMediaPayloads(contentText);
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        int contentLength = Math.max(0, maxLength - EVENT_TEXT_TRUNCATED_SUFFIX.length());
        return sanitized.substring(0, contentLength) + EVENT_TEXT_TRUNCATED_SUFFIX;
    }

    private String stripInlineMediaPayloads(String contentText) {
        String sanitized = contentText.replaceAll(
                "(?i)data:[^\\s\\\"']+;base64,[A-Za-z0-9+/=\\r\\n]+",
                "[inline-media-base64-omitted]"
        );
        return sanitized.replaceAll(
                "(?i)\\\"b64_json\\\"\\s*:\\s*\\\"[A-Za-z0-9+/=\\r\\n]+\\\"",
                "\\\"b64_json\\\":\\\"[inline-media-base64-omitted]\\\""
        );
    }

    private JsonNode parseJsonNode(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isTextual()) {
                return objectMapper.readTree(node.asText());
            }
            return node;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                String text = node.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String extractMediaUrls(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return "";
        }
        List<String> urls = new java.util.ArrayList<>();
        try {
            collectMediaUrls(objectMapper.readTree(contentText), urls);
        } catch (Exception ignored) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("https?://\\S+")
                    .matcher(contentText);
            while (matcher.find() && urls.size() < 6) {
                urls.add(matcher.group().replaceAll("[\\])},，。]+$", ""));
            }
        }
        return String.join(",", urls);
    }

    private List<String> splitMediaUrls(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(6)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (parsed instanceof String string && !string.isBlank()) {
                parsed = objectMapper.readValue(string, Object.class);
            }
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private void collectMediaUrls(JsonNode node, List<String> urls) {
        if (node == null || urls.size() >= 6) {
            return;
        }
        if (node.isTextual()) {
            String value = node.asText("");
            if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/generated/")) {
                urls.add(value);
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectMediaUrls(item, urls));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase();
                if (key.contains("url") || key.contains("image") || key.contains("video") || key.contains("audio")) {
                    collectMediaUrls(entry.getValue(), urls);
                }
            });
        }
    }

    private AgentModelConfig resolveModelConfigEntityForRun(AgentRun run) {
        if (run.getModelConfigId() != null) {
            var config = agentModelConfigMapper.findActiveById(run.getModelConfigId());
            if (config != null) {
                return config;
            }
        }
        var agentConfigs = agentModelConfigMapper.findAgentEnabled();
        return agentConfigs.isEmpty() ? agentModelConfigMapper.findLatest() : agentConfigs.get(0);
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, (content.length() + 3) / 4);
    }

    private String preview(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxChars ? compact : compact.substring(0, maxChars);
    }

    private String normalizeClientRequestId(String clientRequestId) {
        if (clientRequestId == null) {
            return null;
        }
        String trimmed = clientRequestId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CreateAgentMessageResponse tryIdempotentAgentRun(Long userId, String clientRequestId) {
        Optional<AgentRun> existing = agentRunMapper.findByUserIdAndClientRequestId(userId, clientRequestId);
        if (existing.isEmpty()) {
            return null;
        }
        AgentRun run = existing.get();
        Long messageId = run.getSourceUserMessageId();
        if (messageId == null) {
            AgentMessage linked = agentMessageMapper.findUserMessageByRunId(run.getId());
            messageId = linked == null ? null : linked.getId();
        }
        if (messageId == null) {
            return null;
        }
        return new CreateAgentMessageResponse(run.getSessionId(), messageId, run.getId(), mapRunStatusForClient(run.getStatus()));
    }

    private String mapRunStatusForClient(String status) {
        if ("CREATED".equals(status)) {
            return "RUNNING";
        }
        return status;
    }

    private AgentMessage resolveUserMessageForRun(AgentRun run) {
        AgentMessage byRun = agentMessageMapper.findUserMessageByRunId(run.getId());
        if (byRun != null) {
            return byRun;
        }
        if (run.getSourceUserMessageId() != null) {
            return agentMessageMapper.selectById(run.getSourceUserMessageId());
        }
        return null;
    }

    private AgentSession findSession(Long userId, Long sessionId) {
        return agentSessionMapper.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不存在"));
    }

    private void autoUpdateSessionTitle(AgentSession session, String messageContent, LocalDateTime now) {
        if (!"新对话".equals(session.getTitle()) || messageContent.isBlank()) {
            return;
        }
        String title = messageContent.length() > 20 ? messageContent.substring(0, 20) + "…" : messageContent;
        agentSessionMapper.updateTitle(session.getId(), title, now);
    }

    private AgentRun findRun(Long runId, Long userId) {
        return agentRunMapper.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent 运行不存在"));
    }

    private AgentRun findRun(Long runId) {
        return agentRunMapper.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent 运行不存在"));
    }

    private AgentToolCall findToolCall(Long toolCallId) {
        return agentToolCallMapper.findById(toolCallId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "工具调用不存在"));
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void notifyAgentService(Long runId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (isAgentServiceNotificationTimeout(exception)) {
                LOGGER.warn("agent service notification timed out, runId={}, keep run active for async execution", runId, exception);
                AgentRun run = findRun(runId);
                appendEventInternal(
                        runId,
                        run.getUserId(),
                        "run.dispatch_timeout",
                        "Agent 服务启动通知超时，后台将继续等待运行结果",
                        toJson(Map.of(
                                "errorCode", "AGENT_SERVICE_NOTIFY_TIMEOUT",
                                "message", messageOrDefault(exception.getMessage(), "Agent service notification timed out")
                        )),
                        LocalDateTime.now()
                );
                return;
            }
            LOGGER.warn("agent service notification failed, runId={}", runId, exception);
            failRun(runId, new FailAgentRunRequest(
                    "AGENT_SERVICE_NOTIFY_FAILED",
                    "Agent 服务暂时不可用，请稍后重试"
            ));
        }
    }

    private boolean isAgentServiceNotificationTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ModelConnectivityCheck checkModelConnectivity(Long requestedModelConfigId) {
        InternalAgentModelConfigResponse config = agentModelConfigService.internalGet(requestedModelConfigId);
        if (Boolean.FALSE.equals(config.enabled()) || Boolean.FALSE.equals(config.agentEnabled())) {
            return new ModelConnectivityCheck(config, false, "Agent model config is disabled");
        }
        return new ModelConnectivityCheck(config, true, "Agent model config accepted");
    }

    private String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private LocalDateTime firstNonNull(LocalDateTime primary, LocalDateTime fallback) {
        return primary == null ? fallback : primary;
    }

    private record ModelConnectivityCheck(InternalAgentModelConfigResponse config, boolean success, String message) {
    }

    private AgentRunEvent appendEventInternal(Long runId, Long userId, String eventType, String eventText,
                                              String eventJson, LocalDateTime now) {
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setEventText(eventTextPreview(eventText));
        event.setEventJson(eventJson);
        event.setCreatedAt(now);
        agentRunEventMapper.insertEvent(event);
        publishEvent(AgentRunEventResponse.from(event));
        return event;
    }

    private void publishEvent(AgentRunEventResponse event) {
        CopyOnWriteArrayList<SseEmitter> emitters = eventStreams.get(event.runId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            sendEvent(event.runId(), emitter, event);
        }
    }

    private boolean isTerminalRunEvent(AgentRunEventResponse event) {
        return "run.completed".equals(event.eventType()) || "run.failed".equals(event.eventType());
    }

    private String eventTextPreview(String eventText) {
        if (eventText == null || eventText.length() <= MAX_EVENT_TEXT_LENGTH) {
            return eventText;
        }
        int contentLength = Math.max(0, MAX_EVENT_TEXT_LENGTH - EVENT_TEXT_TRUNCATED_SUFFIX.length());
        return eventText.substring(0, contentLength) + EVENT_TEXT_TRUNCATED_SUFFIX;
    }

    private String errorMessagePreview(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return errorMessage;
        }
        int contentLength = Math.max(0, MAX_ERROR_MESSAGE_LENGTH - EVENT_TEXT_TRUNCATED_SUFFIX.length());
        return errorMessage.substring(0, contentLength) + EVENT_TEXT_TRUNCATED_SUFFIX;
    }

    private Map<String, Object> toolFinishedEventJson(AgentToolCall call,
                                                      String status,
                                                      Object resultJson,
                                                      String errorCode,
                                                      String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (resultJson instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key instanceof String name) {
                    payload.put(name, value);
                }
            });
        } else if (resultJson != null) {
            payload.put("result", resultJson);
        }
        payload.put("toolCode", call.getToolCode());
        payload.put("toolCallId", call.getId());
        if (call.getTaskId() != null) {
            payload.put("taskId", call.getTaskId());
        }
        payload.put("status", status);
        if (errorCode != null && !errorCode.isBlank()) {
            payload.put("errorCode", errorCode);
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            payload.put("errorMessage", errorMessage);
        }
        return payload;
    }

    private void sendEvent(Long runId, SseEmitter emitter, AgentRunEventResponse event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.id()))
                    .name(event.eventType())
                    .data(event));
        } catch (IOException | IllegalStateException exception) {
            removeEmitter(runId, emitter);
        }
    }

    private void removeEmitter(Long runId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = eventStreams.get(runId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            eventStreams.remove(runId);
        }
    }

    private void cleanupEventStreams(Long runId) {
        CopyOnWriteArrayList<SseEmitter> emitters = eventStreams.remove(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter already completed or errored
            }
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "JSON 序列化失败");
        }
    }

    private String toJsonOrEmpty(Object value) {
        String json = toJson(value);
        return json == null ? "{}" : json;
    }

    private String toolEventJson(AgentToolCall call) {
        try {
            return objectMapper.writeValueAsString(new AgentToolCallResponse(
                    call.getId(),
                    call.getRunId(),
                    call.getToolCode(),
                    call.getTaskId(),
                    call.getStatus(),
                    call.getArgumentsJson(),
                    call.getResultJson(),
                    call.getErrorCode(),
                    call.getErrorMessage(),
                    call.getStartedAt(),
                    call.getFinishedAt(),
                    call.getCreatedAt()
            ));
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
