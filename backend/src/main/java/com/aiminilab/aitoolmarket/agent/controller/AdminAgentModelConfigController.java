package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/list")
    public ApiResponse<List<AgentModelConfigResponse>> list() {
        return ApiResponse.success(agentModelConfigService.adminList());
    }

    @PutMapping
    public ApiResponse<AgentModelConfigResponse> save(@Valid @RequestBody AgentModelConfigRequest request) {
        return ApiResponse.success(agentModelConfigService.adminSave(request));
    }

    @PostMapping
    public ApiResponse<AgentModelConfigResponse> create(@Valid @RequestBody AgentModelConfigRequest request) {
        return ApiResponse.success(agentModelConfigService.adminCreate(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentModelConfigResponse> update(@PathVariable Long id,
                                                        @Valid @RequestBody AgentModelConfigRequest request) {
        return ApiResponse.success(agentModelConfigService.adminUpdate(id, request));
    }

    @PostMapping("/{id}/default")
    public ApiResponse<AgentModelConfigResponse> setDefault(@PathVariable Long id) {
        return ApiResponse.success(agentModelConfigService.adminSetDefault(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentModelConfigService.adminDelete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/test")
    public ApiResponse<AgentModelConfigTestResponse> test(@Valid @RequestBody AgentModelConfigRequest request) {
        return ApiResponse.success(agentModelConfigService.adminTest(request));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<AgentModelConfigTestResponse> testById(@PathVariable Long id) {
        return ApiResponse.success(agentModelConfigService.adminTestById(id));
    }
}
