package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentFileResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentGraphCheckpointResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.DelegatedWorkflowToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.BindAgentToolCallTaskRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentArtifactRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentSessionSearchItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentSessionSearchRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpsertAgentGraphCheckpointRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpsertLangGraphCheckpointRequest;
import com.aiminilab.aitoolmarket.agent.dto.LangGraphCheckpointResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpsertLangGraphCheckpointWritesRequest;
import com.aiminilab.aitoolmarket.agent.dto.SearchLangGraphCheckpointsRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpsertStreamingAgentAnswerRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSkillBundleResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalCreateWorkspaceMemoryCandidateRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalCreateWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentConversationSummaryRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateModelRequestSnapshotsRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelRequestSnapshotResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentAuditService;
import com.aiminilab.aitoolmarket.agent.service.AgentFileService;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentSkillBundleService;
import com.aiminilab.aitoolmarket.agent.mapper.AgentWorkspaceMemoryItemMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkflowDelegationService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import java.time.LocalDateTime;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/agent")
public class InternalAgentController {

    private final AgentRunService agentRunService;
    private final AgentSkillBundleService agentSkillBundleService;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentFileService agentFileService;
    private final AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper;
    private final AgentAuditService agentAuditService;
    private final AgentWorkflowDelegationService workflowDelegationService;

    public InternalAgentController(AgentRunService agentRunService,
                                   AgentSkillBundleService agentSkillBundleService,
                                   AgentModelConfigService agentModelConfigService,
                                   AgentWorkspaceService agentWorkspaceService,
                                   AgentFileService agentFileService,
                                   AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper,
                                   AgentAuditService agentAuditService,
                                   AgentWorkflowDelegationService workflowDelegationService) {
        this.agentRunService = agentRunService;
        this.agentSkillBundleService = agentSkillBundleService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentFileService = agentFileService;
        this.agentWorkspaceMemoryItemMapper = agentWorkspaceMemoryItemMapper;
        this.agentAuditService = agentAuditService;
        this.workflowDelegationService = workflowDelegationService;
    }

    @GetMapping("/runs/{runId}/context")
    public ApiResponse<InternalAgentRunContextResponse> context(@PathVariable Long runId) {
        return ApiResponse.success(agentRunService.context(runId));
    }

    @PostMapping("/runs/{runId}/model-request-snapshots")
    public ApiResponse<java.util.List<AgentModelRequestSnapshotResponse>> modelRequestSnapshots(
            @PathVariable Long runId,
            @Valid @RequestBody CreateModelRequestSnapshotsRequest request) {
        return ApiResponse.success(request.snapshots().stream()
                .map(item -> agentAuditService.recordModelRequest(runId, item))
                .toList());
    }

    @GetMapping("/skills/{skillCode}")
    public ApiResponse<AgentSkillBundleResponse> skill(@PathVariable String skillCode) {
        return ApiResponse.success(agentSkillBundleService.getPublished(skillCode));
    }

    @GetMapping("/model-config")
    public ApiResponse<InternalAgentModelConfigResponse> modelConfig() {
        return ApiResponse.success(agentModelConfigService.internalGet());
    }

    @PostMapping("/workspaces/{workspaceId}/memory/retrieve")
    public ApiResponse<PageResponse<InternalWorkspaceMemoryItemResponse>> retrieveMemory(
            @PathVariable Long workspaceId,
            @Valid @RequestBody InternalWorkspaceMemoryRetrieveRequest request
    ) {
        return ApiResponse.success(agentWorkspaceService.retrieveMemory(workspaceId, request));
    }

    @PostMapping("/workspaces/{workspaceId}/memory")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> createMemory(
            @PathVariable Long workspaceId,
            @RequestBody InternalCreateWorkspaceMemoryRequest request
    ) {
        var createRequest = new com.aiminilab.aitoolmarket.agent.dto.CreateAgentWorkspaceMemoryRequest(
                request.memoryType(), request.title(), request.content(), request.sourceRunId(),
                request.sourceMessageId(), request.sourceToolCallId(), request.importance(), request.confidence(),
                request.pinned(), request.tagsJson(), request.metadataJson(), request.expiresAt()
        );
        return ApiResponse.success(agentWorkspaceService.createMemory(request.userId(), workspaceId, createRequest));
    }

    @PostMapping("/workspaces/{workspaceId}/memory/candidates")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> createMemoryCandidate(
            @PathVariable Long workspaceId,
            @RequestBody InternalCreateWorkspaceMemoryCandidateRequest request
    ) {
        return ApiResponse.success(agentWorkspaceService.createMemoryCandidate(workspaceId, request));
    }

    @PostMapping("/session-search")
    public ApiResponse<PageResponse<InternalAgentSessionSearchItemResponse>> searchSession(
            @Valid @RequestBody InternalAgentSessionSearchRequest request
    ) {
        return ApiResponse.success(agentWorkspaceService.searchSession(request));
    }

    @PutMapping("/workspaces/{workspaceId}/memory/{memoryId}")
    public ApiResponse<AgentWorkspaceMemoryItemResponse> updateMemory(
            @PathVariable Long workspaceId,
            @PathVariable Long memoryId,
            @RequestBody UpdateAgentWorkspaceMemoryRequest request
    ) {
        var now = LocalDateTime.now();
        var entity = new com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMemoryItem();
        entity.setId(memoryId);
        entity.setWorkspaceId(workspaceId);
        entity.setMemoryType(request.memoryType());
        entity.setTitle(request.title());
        entity.setContent(request.content());
        entity.setImportance(request.importance());
        entity.setConfidence(request.confidence());
        entity.setPinned(request.pinned());
        entity.setTagsJson(request.tagsJson());
        entity.setMetadataJson(request.metadataJson());
        entity.setExpiresAt(request.expiresAt());
        entity.setUpdatedAt(now);
        agentWorkspaceMemoryItemMapper.updateById(entity);
        return ApiResponse.success(new AgentWorkspaceMemoryItemResponse(
                memoryId, workspaceId, null, request.memoryType(),
                request.title(), request.content(), null, null, null, request.importance(), request.confidence(),
                Boolean.TRUE.equals(request.pinned()), request.tagsJson(), request.metadataJson(), null, null,
                request.expiresAt(), "ACTIVE", now, now
        ));
    }

    @DeleteMapping("/workspaces/{workspaceId}/memory/{memoryId}")
    public ApiResponse<Void> deleteMemory(
            @PathVariable Long workspaceId,
            @PathVariable Long memoryId
    ) {
        int affected = agentWorkspaceMemoryItemMapper.softDelete(workspaceId, memoryId);
        if (affected == 0) {
            throw new com.aiminilab.aitoolmarket.common.exception.BusinessException(
                    com.aiminilab.aitoolmarket.common.enums.ErrorCode.PARAM_ERROR,
                    "Workspace memory item not found"
            );
        }
        return ApiResponse.success(null);
    }

    @PostMapping("/runs/{runId}/events")
    public ApiResponse<AgentRunEventResponse> appendEvent(@PathVariable Long runId,
                                                          @Valid @RequestBody CreateAgentRunEventRequest request) {
        return ApiResponse.success(agentRunService.appendEvent(runId, request));
    }

    @PostMapping("/runs/{runId}/artifacts")
    public ApiResponse<AgentFileResponse> createArtifact(@PathVariable Long runId,
                                                         @RequestBody CreateAgentArtifactRequest request) {
        return ApiResponse.success(agentFileService.createArtifactForRun(runId, request));
    }

    @PostMapping("/runs/{runId}/tool-calls")
    public ApiResponse<AgentToolCallResponse> createToolCall(@PathVariable Long runId,
                                                             @Valid @RequestBody CreateAgentToolCallRequest request) {
        return ApiResponse.success(agentRunService.createToolCall(runId, request));
    }

    @PostMapping("/tool-calls/{toolCallId}/task")
    public ApiResponse<AgentToolCallResponse> bindToolCallTask(@PathVariable Long toolCallId,
                                                               @Valid @RequestBody BindAgentToolCallTaskRequest request) {
        return ApiResponse.success(agentRunService.bindToolCallTask(toolCallId, request));
    }

    @PostMapping("/tool-calls/{toolCallId}/delegate-workflow")
    public ApiResponse<DelegatedWorkflowToolCallResponse> delegateWorkflow(@PathVariable Long toolCallId) {
        return ApiResponse.success(workflowDelegationService.delegate(toolCallId));
    }

    @PostMapping("/tool-calls/{toolCallId}/complete")
    public ApiResponse<AgentToolCallResponse> completeToolCall(@PathVariable Long toolCallId,
                                                               @RequestBody(required = false) CompleteAgentToolCallRequest request) {
        return ApiResponse.success(agentRunService.completeToolCall(
                toolCallId,
                request == null ? new CompleteAgentToolCallRequest(null) : request
        ));
    }

    @PostMapping("/tool-calls/{toolCallId}/fail")
    public ApiResponse<AgentToolCallResponse> failToolCall(@PathVariable Long toolCallId,
                                                           @Valid @RequestBody FailAgentToolCallRequest request) {
        return ApiResponse.success(agentRunService.failToolCall(toolCallId, request));
    }

    @PutMapping("/runs/{runId}/streaming-answer")
    public ApiResponse<AgentRunResponse> upsertStreamingAnswer(
            @PathVariable Long runId,
            @Valid @RequestBody UpsertStreamingAgentAnswerRequest request
    ) {
        return ApiResponse.success(agentRunService.upsertStreamingAnswer(runId, request));
    }

    @PutMapping("/runs/{runId}/conversation-summary")
    public ApiResponse<AgentRunResponse> updateConversationSummary(
            @PathVariable Long runId,
            @RequestBody UpdateAgentConversationSummaryRequest request
    ) {
        return ApiResponse.success(agentRunService.updateConversationSummary(
                runId,
                request == null ? null : request.conversationSummary()
        ));
    }

    @PutMapping("/runs/{runId}/graph-checkpoint")
    public ApiResponse<AgentGraphCheckpointResponse> saveGraphCheckpoint(
            @PathVariable Long runId,
            @Valid @RequestBody UpsertAgentGraphCheckpointRequest request
    ) {
        agentRunService.saveGraphCheckpoint(runId, request.checkpointJson());
        return ApiResponse.success(new AgentGraphCheckpointResponse(runId, request.checkpointJson()));
    }

    @GetMapping("/runs/{runId}/graph-checkpoint")
    public ApiResponse<AgentGraphCheckpointResponse> getGraphCheckpoint(@PathVariable Long runId) {
        return ApiResponse.success(new AgentGraphCheckpointResponse(runId, agentRunService.getGraphCheckpoint(runId)));
    }

    @DeleteMapping("/runs/{runId}/graph-checkpoint")
    public ApiResponse<Void> clearGraphCheckpoint(@PathVariable Long runId) {
        agentRunService.clearGraphCheckpoint(runId);
        return ApiResponse.success(null);
    }

    @PutMapping("/runs/{runId}/langgraph-checkpoint")
    public ApiResponse<LangGraphCheckpointResponse> saveLangGraphCheckpoint(
            @PathVariable Long runId,
            @Valid @RequestBody UpsertLangGraphCheckpointRequest request) {
        agentRunService.saveLangGraphCheckpoint(runId, request.threadId(), request.checkpointNs(), request.checkpointId(),
                request.checkpointSerde(), request.checkpointPayload(), request.metadataJson(), request.parentCheckpointId());
        return ApiResponse.success(agentRunService.getLangGraphCheckpoint(runId, request.threadId(), request.checkpointNs(), request.checkpointId()));
    }

    @GetMapping("/runs/{runId}/langgraph-checkpoint")
    public ApiResponse<LangGraphCheckpointResponse> getLangGraphCheckpoint(
            @PathVariable Long runId, @org.springframework.web.bind.annotation.RequestParam String threadId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String checkpointNs,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String checkpointId) {
        return ApiResponse.success(agentRunService.getLangGraphCheckpoint(runId, threadId, checkpointNs, checkpointId));
    }

    @PutMapping("/runs/{runId}/langgraph-checkpoint-writes")
    public ApiResponse<Void> saveLangGraphCheckpointWrites(@PathVariable Long runId,
            @Valid @RequestBody UpsertLangGraphCheckpointWritesRequest request) {
        agentRunService.saveLangGraphCheckpointWrites(runId, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/runs/{runId}/langgraph-checkpoints/search")
    public ApiResponse<java.util.Map<String, Object>> listLangGraphCheckpoints(@PathVariable Long runId,
            @Valid @RequestBody SearchLangGraphCheckpointsRequest request) {
        return ApiResponse.success(java.util.Map.of("list", agentRunService.listLangGraphCheckpoints(runId, request.threadId(),
                request.checkpointNs(), request.beforeCheckpointId(), request.limit(), request.metadataFilter())));
    }

    @DeleteMapping("/runs/{runId}/langgraph-checkpoint")
    public ApiResponse<Void> clearLangGraphCheckpoint(
            @PathVariable Long runId, @org.springframework.web.bind.annotation.RequestParam String threadId) {
        agentRunService.clearLangGraphCheckpoint(runId, threadId);
        return ApiResponse.success(null);
    }

    @PostMapping("/runs/{runId}/complete")
    public ApiResponse<AgentRunResponse> completeRun(@PathVariable Long runId,
                                                     @Valid @RequestBody CompleteAgentRunRequest request) {
        return ApiResponse.success(agentRunService.completeRun(runId, request));
    }

    @PostMapping("/runs/{runId}/fail")
    public ApiResponse<AgentRunResponse> failRun(@PathVariable Long runId,
                                                 @Valid @RequestBody FailAgentRunRequest request) {
        return ApiResponse.success(agentRunService.failRun(runId, request));
    }
}
