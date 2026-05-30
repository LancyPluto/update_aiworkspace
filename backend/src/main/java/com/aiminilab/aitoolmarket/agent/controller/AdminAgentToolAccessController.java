package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.config.AgentPromptSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRouterSettings;
import com.aiminilab.aitoolmarket.agent.dto.AgentRouterSettingsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRouteDebugRequest;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRouteDebugResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentToolAccessResponse;
import com.aiminilab.aitoolmarket.agent.dto.BulkUpdateAgentToolAccessRequest;
import com.aiminilab.aitoolmarket.agent.dto.BulkUpdateAgentToolAccessResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolAccessRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/v1/agent/tools")
public class AdminAgentToolAccessController {

    private final AgentToolDescriptorService agentToolDescriptorService;
    private final AgentModelConfigService agentModelConfigService;
    private final SystemSettingService systemSettingService;
    private final AgentServiceClient agentServiceClient;

    public AdminAgentToolAccessController(AgentToolDescriptorService agentToolDescriptorService,
                                          AgentModelConfigService agentModelConfigService,
                                          SystemSettingService systemSettingService,
                                          AgentServiceClient agentServiceClient) {
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.agentModelConfigService = agentModelConfigService;
        this.systemSettingService = systemSettingService;
        this.agentServiceClient = agentServiceClient;
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

    @PutMapping("/bulk-access")
    public ApiResponse<BulkUpdateAgentToolAccessResponse> bulkUpdate(@Valid @RequestBody BulkUpdateAgentToolAccessRequest request) {
        List<AdminAgentToolAccessResponse> updated = new java.util.ArrayList<>();
        List<BulkUpdateAgentToolAccessResponse.FailedToolUpdate> failed = new java.util.ArrayList<>();
        for (String toolCode : request.toolCodes()) {
            try {
                updated.add(agentToolDescriptorService.updateAdminToolAccess(
                        toolCode,
                        new UpdateAgentToolAccessRequest(request.agentEnabled())
                ));
            } catch (Exception exception) {
                failed.add(new BulkUpdateAgentToolAccessResponse.FailedToolUpdate(toolCode, exception.getMessage()));
            }
        }
        return ApiResponse.success(new BulkUpdateAgentToolAccessResponse(updated, failed));
    }

    @PostMapping("/route-debug")
    public ApiResponse<AdminAgentRouteDebugResponse> debugRoute(@Valid @RequestBody AdminAgentRouteDebugRequest request) {
        Long userId = AuthContext.get().userId();
        var availableTools = agentToolDescriptorService.listAvailableToolsForUser(userId);
        Set<String> visibleCodes = availableTools.stream()
                .map(tool -> tool.toolCode())
                .collect(java.util.stream.Collectors.toSet());
        List<AdminAgentRouteDebugResponse.RouteDebugFilteredToolResponse> filteredTools =
                agentToolDescriptorService.listAdminToolAccess().stream()
                        .filter(tool -> !visibleCodes.contains(tool.toolCode()))
                        .map(tool -> new AdminAgentRouteDebugResponse.RouteDebugFilteredToolResponse(
                                tool.toolCode(),
                                tool.toolName(),
                                filteredReason(tool)
                        ))
                        .toList();
        Map<String, String> settings = systemSettingService.settings();
        var context = new InternalAgentRunContextResponse(
                0L,
                0L,
                null,
                userId,
                "DEBUG",
                request.message(),
                List.of(),
                List.of(),
                List.of(),
                availableTools,
                List.of(),
                0,
                null,
                agentModelConfigService.internalGet(),
                nonBlankOrDefault(settings.get(AgentPromptSettings.SYSTEM_PROMPT_KEY), AgentPromptSettings.DEFAULT_SYSTEM_PROMPT),
                nonBlankOrDefault(settings.get(AgentPromptSettings.DEEP_AGENTS_SYSTEM_PROMPT_KEY), AgentPromptSettings.DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT),
                null,
                new AgentRouterSettingsResponse(
                        parseBooleanSetting(settings.get(AgentRouterSettings.ENABLED_KEY), AgentRouterSettings.DEFAULT_ENABLED),
                        nonBlankOrDefault(settings.get(AgentRouterSettings.PROMPT_KEY), AgentRouterSettings.DEFAULT_PROMPT),
                        parseDoubleSetting(settings.get(AgentRouterSettings.MIN_CONFIDENCE_KEY), 0.7D, 0D, 1D),
                        parseBooleanSetting(settings.get(AgentRouterSettings.FALLBACK_TO_RULES_KEY), AgentRouterSettings.DEFAULT_FALLBACK_TO_RULES)
                ),
                null
        );
        AdminAgentRouteDebugResponse response = agentServiceClient.debugRoute(context);
        return ApiResponse.success(new AdminAgentRouteDebugResponse(
                response.intent(),
                response.confidence(),
                response.selectedToolCode(),
                response.candidateToolCodes(),
                response.clarifyingQuestion(),
                response.decisionSource(),
                response.reason(),
                response.requestedOutputModality(),
                response.visibleToolCount(),
                response.visibleTools(),
                filteredTools
        ));
    }

    private String filteredReason(AdminAgentToolAccessResponse tool) {
        if (!Boolean.TRUE.equals(tool.agentEnabled())) {
            return "Agent 已禁用";
        }
        if (tool.modelConfigId() == null && (tool.modelName() == null || tool.modelName().isBlank())) {
            return "未绑定模型";
        }
        if ("FAILED".equalsIgnoreCase(tool.healthStatus())) {
            return "健康检查失败";
        }
        return "未进入 Agent 可见工具清单";
    }

    private String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean parseBooleanSetting(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private double parseDoubleSetting(String value, double fallback, double min, double max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
