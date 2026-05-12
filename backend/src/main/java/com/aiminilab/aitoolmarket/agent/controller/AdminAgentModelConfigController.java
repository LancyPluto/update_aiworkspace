package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/agent/model-config")
public class AdminAgentModelConfigController {

    private final AgentModelConfigService agentModelConfigService;

    public AdminAgentModelConfigController(AgentModelConfigService agentModelConfigService) {
        this.agentModelConfigService = agentModelConfigService;
    }

    @GetMapping
    public ApiResponse<AgentModelConfigResponse> get() {
        return ApiResponse.success(agentModelConfigService.adminGet());
    }

    @PutMapping
    public ApiResponse<AgentModelConfigResponse> save(@Valid @RequestBody AgentModelConfigRequest request) {
        return ApiResponse.success(agentModelConfigService.adminSave(request));
    }

    @PostMapping("/test")
    public ApiResponse<AgentModelConfigTestResponse> test(@Valid @RequestBody AgentModelConfigRequest request) {
        return ApiResponse.success(agentModelConfigService.adminTest(request));
    }
}
