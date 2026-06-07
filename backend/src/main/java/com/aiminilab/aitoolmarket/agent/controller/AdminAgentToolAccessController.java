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
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
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
                        parseBooleanSetting(settings.get(AgentRouterSettings.FALLBACK_TO_RULES_KEY), AgentRouterSettings.DEFAULT_FALLBACK_TO_RULES),
                        parseIntSetting(settings.get(AgentRouterSettings.HISTORY_TURNS_KEY), AgentRouterSettings.DEFAULT_HISTORY_TURNS, 0, 20),
                        parseIntSetting(settings.get(AgentRouterSettings.RECENT_TOOL_CALLS_KEY), AgentRouterSettings.DEFAULT_RECENT_TOOL_CALLS, 0, 10)
                ),
                null,
                List.of(),
                null,
                null
        );
        AdminAgentRouteDebugResponse response;
        try {
            response = agentServiceClient.debugRoute(context);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, routeDebugFailureMessage(exception));
        }
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

    private String routeDebugFailureMessage(IllegalStateException exception) {
        String detail = exception.getMessage() == null ? "" : exception.getMessage();
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        if (detail.contains("HTTP 401") || normalized.contains("invalid internal signature")) {
            return "路由调试器调用 agent-service 失败：内部签名校验未通过（HTTP 401）。"
                    + " 请检查 backend 的 internalApiToken 与 agent-service 的 INTERNAL_API_TOKEN 是否一致，"
                    + "并确认两边机器时间误差不超过 5 分钟。原始信息：" + detail;
        }
        if (normalized.contains("could not notify agent service")) {
            return "路由调试器调用 agent-service 失败：无法连接 agent-service。"
                    + " 请检查 backend 配置的 app.agent.serviceBaseUrl 是否可达（Docker 内通常为 http://agent-service:8090），"
                    + "并确认 agent-service 容器已启动。原始信息：" + detail;
        }
        if (normalized.contains("could not parse agent-service route debug response")) {
            return "路由调试器调用 agent-service 失败：返回内容无法解析。"
                    + " 请确认 backend 与 agent-service 版本匹配，或查看 agent-service 日志。原始信息：" + detail;
        }
        if (normalized.contains("agent service rejected") && normalized.contains("http")) {
            return "路由调试器调用 agent-service 被拒绝：" + detail;
        }
        return "路由调试器调用 agent-service 失败：" + detail;
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

    private int parseIntSetting(String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
