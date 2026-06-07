package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolPickerItemResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent/tools")
public class AgentToolController {

    private final AgentToolDescriptorService agentToolDescriptorService;

    public AgentToolController(AgentToolDescriptorService agentToolDescriptorService) {
        this.agentToolDescriptorService = agentToolDescriptorService;
    }

    @GetMapping
    public ApiResponse<List<AgentToolPickerItemResponse>> list() {
        return ApiResponse.success(agentToolDescriptorService.listPickerToolsForUser(AuthContext.get().userId()));
    }
}
