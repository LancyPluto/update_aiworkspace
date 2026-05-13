package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSessionResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentSessionRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentSessionService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/sessions")
public class AgentSessionController {

    private final AgentSessionService agentSessionService;
    private final AgentRunService agentRunService;

    public AgentSessionController(AgentSessionService agentSessionService, AgentRunService agentRunService) {
        this.agentSessionService = agentSessionService;
        this.agentRunService = agentRunService;
    }

    @PostMapping
    public ApiResponse<AgentSessionResponse> create(@Valid @RequestBody(required = false) CreateAgentSessionRequest request) {
        return ApiResponse.success(agentSessionService.create(AuthContext.get().userId(), request));
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentSessionResponse>> list(@RequestParam(required = false) Integer pageNo,
                                                                @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(agentSessionService.list(AuthContext.get().userId(), pageNo, pageSize));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<AgentSessionResponse> detail(@PathVariable Long sessionId) {
        return ApiResponse.success(agentSessionService.detail(AuthContext.get().userId(), sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<PageResponse<AgentMessageResponse>> messages(@PathVariable Long sessionId,
                                                                    @RequestParam(required = false) Integer pageNo,
                                                                    @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(agentSessionService.messages(AuthContext.get().userId(), sessionId, pageNo, pageSize));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<CreateAgentMessageResponse> sendMessage(@PathVariable Long sessionId,
                                                               @Valid @RequestBody CreateAgentMessageRequest request) {
        return ApiResponse.success(agentRunService.sendMessage(AuthContext.get().userId(), sessionId, request));
    }
}
