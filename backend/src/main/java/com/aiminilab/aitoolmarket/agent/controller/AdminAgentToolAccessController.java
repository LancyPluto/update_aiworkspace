package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentToolAccessResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolAccessRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/agent/tools")
public class AdminAgentToolAccessController {

    private final AgentToolDescriptorService agentToolDescriptorService;

    public AdminAgentToolAccessController(AgentToolDescriptorService agentToolDescriptorService) {
        this.agentToolDescriptorService = agentToolDescriptorService;
    }

    @GetMapping
    public ApiResponse<List<AdminAgentToolAccessResponse>> list() {
        return ApiResponse.success(agentToolDescriptorService.listAdminToolAccess());
    }

    @PutMapping("/{toolCode}")
    public ApiResponse<AdminAgentToolAccessResponse> update(@PathVariable String toolCode,
                                                            @Valid @RequestBody UpdateAgentToolAccessRequest request) {
        return ApiResponse.success(agentToolDescriptorService.updateAdminToolAccess(toolCode, request));
    }
}
