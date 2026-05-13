package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentFileResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentArtifactRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryRetrieveRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentFileService;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkspaceService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public InternalAgentController(AgentRunService agentRunService,
                                   AgentModelConfigService agentModelConfigService,
                                   AgentWorkspaceService agentWorkspaceService,
                                   AgentFileService agentFileService) {
        this.agentRunService = agentRunService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentFileService = agentFileService;
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
