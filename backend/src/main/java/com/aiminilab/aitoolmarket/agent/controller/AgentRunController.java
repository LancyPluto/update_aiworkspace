package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.ConfirmAgentToolRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.RegenerateAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunResponse> detail(@PathVariable Long runId) {
        return ApiResponse.success(agentRunService.detail(AuthContext.get().userId(), runId));
    }

    @PostMapping("/{runId}/cancel")
    public ApiResponse<AgentRunResponse> cancel(@PathVariable Long runId) {
        return ApiResponse.success(agentRunService.cancel(AuthContext.get().userId(), runId));
    }

    @PostMapping("/{runId}/regenerate")
    public ApiResponse<CreateAgentMessageResponse> regenerate(@PathVariable Long runId,
                                                                @RequestBody(required = false) RegenerateAgentRunRequest request) {
        RegenerateAgentRunRequest body = request == null ? new RegenerateAgentRunRequest(null) : request;
        return ApiResponse.success(agentRunService.regenerateRun(AuthContext.get().userId(), runId, body));
    }

    @PostMapping("/{runId}/tool-confirmations")
    public ApiResponse<AgentRunResponse> confirmTool(@PathVariable Long runId,
                                                     @RequestBody ConfirmAgentToolRequest request) {
        return ApiResponse.success(agentRunService.confirmTool(AuthContext.get().userId(), runId, request));
    }

    @GetMapping("/{runId}/events")
    public ApiResponse<PageResponse<AgentRunEventResponse>> events(@PathVariable Long runId,
                                                                   @RequestParam(required = false) Long afterEventId,
                                                                   @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(agentRunService.events(AuthContext.get().userId(), runId, afterEventId, pageSize));
    }

    @GetMapping("/{runId}/events/stream")
    public SseEmitter streamEvents(@PathVariable Long runId,
                                   @RequestParam(required = false) Long afterEventId) {
        return agentRunService.streamEvents(AuthContext.get().userId(), runId, afterEventId);
    }
}
