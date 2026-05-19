package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentFileResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentArtifactRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalCreateWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentWorkspaceMemoryRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentFileService;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.agent.mapper.AgentWorkspaceMemoryItemMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
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
    private final AgentModelConfigService agentModelConfigService;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentFileService agentFileService;
    private final AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper;

    public InternalAgentController(AgentRunService agentRunService,
                                   AgentModelConfigService agentModelConfigService,
                                   AgentWorkspaceService agentWorkspaceService,
                                   AgentFileService agentFileService,
                                   AgentWorkspaceMemoryItemMapper agentWorkspaceMemoryItemMapper) {
        this.agentRunService = agentRunService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentFileService = agentFileService;
        this.agentWorkspaceMemoryItemMapper = agentWorkspaceMemoryItemMapper;
    }

    @GetMapping("/runs/{runId}/context")
    public ApiResponse<InternalAgentRunContextResponse> context(@PathVariable Long runId) {
        return ApiResponse.success(agentRunService.context(runId));
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
                request.memoryType(), request.title(), request.content(), request.sourceRunId()
        );
        return ApiResponse.success(agentWorkspaceService.createMemory(request.userId(), workspaceId, createRequest));
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
        entity.setUpdatedAt(now);
        agentWorkspaceMemoryItemMapper.updateById(entity);
        return ApiResponse.success(new AgentWorkspaceMemoryItemResponse(
                memoryId, workspaceId, null, request.memoryType(),
                request.title(), request.content(), null, "ACTIVE", now, now
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
