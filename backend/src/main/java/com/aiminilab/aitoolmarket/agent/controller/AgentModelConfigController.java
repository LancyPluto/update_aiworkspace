package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentSelectableModelResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent/model-configs")
public class AgentModelConfigController {

    private final AgentModelConfigService agentModelConfigService;

    public AgentModelConfigController(AgentModelConfigService agentModelConfigService) {
        this.agentModelConfigService = agentModelConfigService;
    }

    @GetMapping
    public ApiResponse<List<AgentSelectableModelResponse>> list() {
        return ApiResponse.success(agentModelConfigService.agentSelectableList());
    }
}
