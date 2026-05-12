package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolPreferenceResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolPreferenceRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentToolPreferenceService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent/tool-preferences")
public class AgentToolPreferenceController {

    private final AgentToolPreferenceService agentToolPreferenceService;

    public AgentToolPreferenceController(AgentToolPreferenceService agentToolPreferenceService) {
        this.agentToolPreferenceService = agentToolPreferenceService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentToolPreferenceResponse>> list() {
        List<AgentToolPreferenceResponse> preferences = agentToolPreferenceService.list(AuthContext.get().userId());
        return ApiResponse.success(new PageResponse<>(preferences, preferences.size()));
    }

    @PutMapping("/{toolCode}")
    public ApiResponse<AgentToolPreferenceResponse> update(@PathVariable String toolCode,
                                                           @Valid @RequestBody UpdateAgentToolPreferenceRequest request) {
        return ApiResponse.success(agentToolPreferenceService.update(AuthContext.get().userId(), toolCode, request));
    }
}
