package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.AgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.ConfirmAgentToolRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentFileChunkContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentFileContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolPreferenceRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.aiminilab.aitoolmarket.agent.entity.AgentFileChunk;
import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;
import com.aiminilab.aitoolmarket.agent.entity.AgentSession;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileChunkMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentMessageMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSessionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentRateLimitService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolPreferenceService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AgentRunServiceImpl implements AgentRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunServiceImpl.class);
    private static final int HISTORY_LIMIT = 20;
    private static final int FILE_CONTEXT_LIMIT = 5;
    private static final int FILE_CHUNK_SCAN_LIMIT = 200;
    private static final int FILE_CHUNK_CONTEXT_LIMIT = 5;
    private static final int DEFAULT_EVENT_PAGE_SIZE = 100;
    private static final long EVENT_STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final Set<String> CANCELLABLE_STATUSES = Set.of("CREATED", "RUNNING", "WAITING_USER_CONFIRMATION");
    private static final Set<String> CONFIRMABLE_STATUSES = Set.of("RUNNING", "WAITING_USER_CONFIRMATION");
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT");
    private static final Set<String> TOOL_CALL_TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED");
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> eventStreams = new ConcurrentHashMap<>();

    private final AgentSessionMapper agentSessionMapper;
    private final AgentMessageMapper agentMessageMapper;
    private final AgentFileMapper agentFileMapper;
    private final AgentFileChunkMapper agentFileChunkMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentRunEventMapper agentRunEventMapper;
    private final AgentToolCallMapper agentToolCallMapper;
    private final AgentToolPreferenceMapper agentToolPreferenceMapper;
    private final AgentRateLimitService agentRateLimitService;
    private final AgentToolDescriptorService agentToolDescriptorService;
    private final AgentToolPreferenceService agentToolPreferenceService;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentServiceClient agentServiceClient;
    private final CreditService creditService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public AgentRunServiceImpl(
            AgentSessionMapper agentSessionMapper,
            AgentMessageMapper agentMessageMapper,
            AgentFileMapper agentFileMapper,
            AgentFileChunkMapper agentFileChunkMapper,
            AgentRunMapper agentRunMapper,
            AgentRunEventMapper agentRunEventMapper,
            AgentToolCallMapper agentToolCallMapper,
            AgentToolPreferenceMapper agentToolPreferenceMapper,
            AgentRateLimitService agentRateLimitService,
            AgentToolDescriptorService agentToolDescriptorService,
            AgentToolPreferenceService agentToolPreferenceService,
            AgentModelConfigService agentModelConfigService,
            AgentServiceClient agentServiceClient,
            CreditService creditService,
            AppProperties appProperties,
            ObjectMapper objectMapper
    ) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentMessageMapper = agentMessageMapper;
        this.agentFileMapper = agentFileMapper;
        this.agentFileChunkMapper = agentFileChunkMapper;
        this.agentRunMapper = agentRunMapper;
        this.agentRunEventMapper = agentRunEventMapper;
        this.agentToolCallMapper = agentToolCallMapper;
        this.agentToolPreferenceMapper = agentToolPreferenceMapper;
        this.agentRateLimitService = agentRateLimitService;
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.agentToolPreferenceService = agentToolPreferenceService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentServiceClient = agentServiceClient;
        this.creditService = creditService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public CreateAgentMessageResponse sendMessage(Long userId, Long sessionId, CreateAgentMessageRequest request) {
        AgentSession session = findSession(userId, sessionId);
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不可用");
        }
        agentRateLimitService.checkMessageRate(userId);
        agentRateLimitService.checkRunRate(userId);
        agentRateLimitService.checkActiveRunLimit(userId);
        int creditBudget = Math.max(0, appProperties.getAgent().getDefaultCreditBudget());
        if (creditService.account(userId).available() < creditBudget) {
            throw new BusinessException(ErrorCode.AGENT_CREDIT_NOT_ENOUGH, "Agent 可用算力不足");
        }

        LocalDateTime now = LocalDateTime.now();
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole("USER");
        message.setContentText(request.content().trim());
        message.setCreatedAt(now);
        agentMessageMapper.insertMessage(message);

        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("CREATED");
        run.setEstimatedCredits(creditBudget);
        run.setConsumedCredits(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        agentRunMapper.insertRun(run);

        message.setRunId(run.getId());
        // Use BaseMapper update here so the generated message id can be linked after run creation.
        agentMessageMapper.updateById(message);

        ModelConnectivityCheck connectivity = checkModelConnectivity();
        run.setModelProviderCode(connectivity.config().provider());
        run.setModelName(connectivity.config().modelName());
        agentRunMapper.updateModel(
                run.getId(),
                connectivity.config().provider(),
                connectivity.config().modelName(),
                now
        );
        if (!connectivity.success()) {
            String errorMessage = messageOrDefault(connectivity.message(), "Agent model connectivity check failed");
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

        creditService.freezeForAgentRun(userId, run.getId(), creditBudget);
        agentRunMapper.markRunning(run.getId(), now);
        agentRateLimitService.incrementActiveRun(userId, run.getId());
        appendEventInternal(run.getId(), userId, "run.started", "Agent 已开始处理", null, now);
        agentSessionMapper.touch(sessionId, now);
        runAfterCommit(() -> notifyAgentService(run.getId(), () -> agentServiceClient.executeRun(run.getId())));
        return new CreateAgentMessageResponse(sessionId, message.getId(), run.getId(), "RUNNING");
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
        agentRunMapper.markCancelled(runId, now);
        creditService.releaseForAgentRun(userId, runId, Math.max(0, run.getEstimatedCredits()));
        agentRateLimitService.decrementActiveRun(userId, runId);
        appendEventInternal(runId, userId, "run.failed", "Agent 运行已取消", "{\"status\":\"CANCELLED\"}", now);
        return AgentRunResponse.from(findRun(runId, userId));
    }

    @Override
    @Transactional
    public AgentRunResponse confirmTool(Long userId, Long runId, ConfirmAgentToolRequest request) {
        AgentRun run = findRun(runId, userId);
        if (!CONFIRMABLE_STATUSES.contains(run.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_CANCELLABLE, "当前 Agent 运行不可确认工具调用");
        }
        if (Boolean.FALSE.equals(request.approved())) {
            LocalDateTime now = LocalDateTime.now();
            agentRunMapper.markCancelled(runId, now);
            creditService.releaseForAgentRun(userId, runId, Math.max(0, run.getEstimatedCredits()));
            agentRateLimitService.decrementActiveRun(userId, runId);
            appendEventInternal(runId, userId, "run.failed", "用户取消工具调用", "{\"status\":\"CANCELLED\"}", now);
            return AgentRunResponse.from(findRun(runId, userId));
        }
        agentToolDescriptorService.getToolForAgent(userId, request.toolCode());
        LocalDateTime now = LocalDateTime.now();
        if (request.autoCallEnabled() != null) {
            agentToolPreferenceService.update(userId, request.toolCode(), new UpdateAgentToolPreferenceRequest(request.autoCallEnabled()));
        }
        if ("WAITING_USER_CONFIRMATION".equals(run.getStatus())) {
            agentRunMapper.markRunning(runId, now);
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
            agentRunEventMapper.findEvents(userId, runId, afterEventId, DEFAULT_EVENT_PAGE_SIZE)
                    .stream()
                    .map(AgentRunEventResponse::from)
                    .forEach(event -> sendEvent(runId, emitter, event));
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
        List<InternalAgentMessageResponse> history = agentMessageMapper.findLatestBySession(run.getSessionId(), HISTORY_LIMIT)
                .stream()
                .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                .map(message -> new InternalAgentMessageResponse(message.getRole(), message.getContentText()))
                .toList();
        List<AgentFile> readyFiles = agentFileMapper.findReadyBySession(
                run.getUserId(),
                run.getSessionId(),
                FILE_CONTEXT_LIMIT
        );
        Map<Long, String> filenames = readyFiles.stream()
                .collect(java.util.stream.Collectors.toMap(AgentFile::getId, AgentFile::getOriginalFilename));
        List<InternalAgentFileContextResponse> agentFiles = readyFiles
                .stream()
                .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                .map(InternalAgentFileContextResponse::from)
                .toList();
        List<InternalAgentFileChunkContextResponse> agentFileChunks = retrieveRelevantFileChunks(
                run,
                userMessage == null ? "" : userMessage.getContentText(),
                filenames
        );
        List<AgentToolDescriptorResponse> tools = agentToolDescriptorService.listAvailableToolsForUser(run.getUserId());
        var preferences = agentToolPreferenceMapper.findByUserId(run.getUserId())
                .stream()
                .map(com.aiminilab.aitoolmarket.agent.dto.AgentToolPreferenceResponse::from)
                .toList();
        return new InternalAgentRunContextResponse(
                run.getId(),
                run.getSessionId(),
                findSession(run.getUserId(), run.getSessionId()).getWorkspaceId(),
                run.getUserId(),
                userMessage == null ? "" : userMessage.getContentText(),
                history,
                agentFiles,
                agentFileChunks,
                tools,
                preferences,
                run.getEstimatedCredits()
        );
    }

    private List<InternalAgentFileChunkContextResponse> retrieveRelevantFileChunks(
            AgentRun run,
            String query,
            Map<Long, String> filenames
    ) {
        List<AgentFileChunk> chunks = agentFileChunkMapper.findReadyBySession(
                        run.getUserId(),
                        run.getSessionId(),
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
        return AgentRunEventResponse.from(appendEventInternal(
                runId,
                run.getUserId(),
                request.eventType(),
                request.eventText(),
                toJson(request.eventJson()),
                LocalDateTime.now()
        ));
    }

    @Override
    @Transactional
    public AgentToolCallResponse createToolCall(Long runId, CreateAgentToolCallRequest request) {
        AgentRun run = findRun(runId);
        agentToolDescriptorService.getToolForAgent(run.getUserId(), request.toolCode());
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
    public AgentToolCallResponse completeToolCall(Long toolCallId, CompleteAgentToolCallRequest request) {
        AgentToolCall call = findToolCall(toolCallId);
        if (TOOL_CALL_TERMINAL_STATUSES.contains(call.getStatus())) {
            return AgentToolCallResponse.from(call);
        }
        LocalDateTime now = LocalDateTime.now();
        agentToolCallMapper.markSuccess(toolCallId, toJson(request.resultJson()), now);
        appendEventInternal(call.getRunId(), call.getUserId(), "tool.finished", "工具调用已完成", toJson(request.resultJson()), now);
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
        agentToolCallMapper.markFailed(toolCallId, request.errorCode(), request.errorMessage(), now);
        appendEventInternal(call.getRunId(), call.getUserId(), "tool.finished", request.errorMessage(), toJson(request), now);
        return AgentToolCallResponse.from(findToolCall(toolCallId));
    }

    @Override
    @Transactional
    public AgentRunResponse completeRun(Long runId, CompleteAgentRunRequest request) {
        AgentRun run = findRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return AgentRunResponse.from(run);
        }
        LocalDateTime now = LocalDateTime.now();
        AgentMessage assistant = new AgentMessage();
        assistant.setSessionId(run.getSessionId());
        assistant.setUserId(run.getUserId());
        assistant.setRole("ASSISTANT");
        assistant.setContentText(request.finalAnswer());
        assistant.setRunId(runId);
        assistant.setCreatedAt(now);
        agentMessageMapper.insertMessage(assistant);
        int estimatedCredits = run.getEstimatedCredits() == null ? 0 : Math.max(0, run.getEstimatedCredits());
        int consumedCredits = request.consumedCredits() == null ? 0 : Math.max(0, Math.min(request.consumedCredits(), estimatedCredits));
        creditService.settleForAgentRun(run.getUserId(), runId, consumedCredits);
        creditService.releaseForAgentRun(run.getUserId(), runId, estimatedCredits - consumedCredits);
        agentRunMapper.markSuccess(runId, request.intent(), request.modelProviderCode(), request.modelName(), consumedCredits, now);
        appendEventInternal(runId, run.getUserId(), "run.completed", "Agent 运行已完成", null, now);
        agentSessionMapper.touch(run.getSessionId(), now);
        agentRateLimitService.decrementActiveRun(run.getUserId(), runId);
        return AgentRunResponse.from(findRun(runId));
    }

    @Override
    @Transactional
    public AgentRunResponse failRun(Long runId, FailAgentRunRequest request) {
        AgentRun run = findRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return AgentRunResponse.from(run);
        }
        LocalDateTime now = LocalDateTime.now();
        agentRunMapper.markFailed(runId, request.errorCode(), request.errorMessage(), now);
        creditService.releaseForAgentRun(run.getUserId(), runId, Math.max(0, run.getEstimatedCredits()));
        appendEventInternal(runId, run.getUserId(), "run.failed", request.errorMessage(), toJson(request), now);
        agentRateLimitService.decrementActiveRun(run.getUserId(), runId);
        return AgentRunResponse.from(findRun(runId));
    }

    private AgentSession findSession(Long userId, Long sessionId) {
        return agentSessionMapper.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "会话不存在"));
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
            LOGGER.warn("agent service notification failed, runId={}", runId, exception);
            failRun(runId, new FailAgentRunRequest(
                    "AGENT_SERVICE_NOTIFY_FAILED",
                    "Agent 服务暂时不可用，请稍后重试"
            ));
        }
    }

    private ModelConnectivityCheck checkModelConnectivity() {
        InternalAgentModelConfigResponse config = agentModelConfigService.internalGet();
        if (Boolean.FALSE.equals(config.enabled())) {
            return new ModelConnectivityCheck(config, false, "Agent model config is disabled");
        }
        AgentModelConfigRequest request = new AgentModelConfigRequest(
                null,
                null,
                config.provider(),
                config.modelName(),
                config.baseUrl(),
                config.apiKey(),
                config.minimaxGroupId(),
                config.timeoutSeconds(),
                config.enabled(),
                null
        );
        AgentModelConfigTestResponse result;
        try {
            result = agentServiceClient.testModelConfig(request);
        } catch (RuntimeException exception) {
            return new ModelConnectivityCheck(config, false, messageOrDefault(
                    exception.getMessage(),
                    "Agent model connectivity check failed"
            ));
        }
        if (result == null || !result.success()) {
            return new ModelConnectivityCheck(config, false, messageOrDefault(
                    result == null ? null : result.message(),
                    "Agent model connectivity check failed"
            ));
        }
        return new ModelConnectivityCheck(config, true, result.message());
    }

    private String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private record ModelConnectivityCheck(InternalAgentModelConfigResponse config, boolean success, String message) {
    }

    private AgentRunEvent appendEventInternal(Long runId, Long userId, String eventType, String eventText,
                                              String eventJson, LocalDateTime now) {
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setEventText(eventText);
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
