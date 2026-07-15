package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunDetailResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;

import java.util.List;
import com.aiminilab.aitoolmarket.agent.service.AdminAgentRunService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import com.aiminilab.aitoolmarket.agent.service.AgentAuditService;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunAuditResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelRequestSnapshotResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunAuditReviewResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentRunAuditReviewRequest;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;

@RestController
@RequestMapping("/api/admin/v1/agent/runs")
public class AdminAgentRunController {

    private final AdminAgentRunService adminAgentRunService;
    private final AgentAuditService agentAuditService;

    public AdminAgentRunController(AdminAgentRunService adminAgentRunService, AgentAuditService agentAuditService) {
        this.adminAgentRunService = adminAgentRunService;
        this.agentAuditService = agentAuditService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminAgentRunListItemResponse>> list(@RequestParam(required = false) String status,
                                                                         @RequestParam(required = false) Long userId,
                                                                         @RequestParam(required = false) Long taskId,
                                                                         @RequestParam(required = false) Integer pageNo,
                                                                         @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(adminAgentRunService.list(status, userId, taskId, pageNo, pageSize));
    }

    @GetMapping("/stats")
    public ApiResponse<AdminAgentRunStatsResponse> stats() {
        return ApiResponse.success(adminAgentRunService.stats());
    }

    @GetMapping("/{runId}")
    public ApiResponse<AdminAgentRunDetailResponse> detail(@PathVariable Long runId) {
        return ApiResponse.success(adminAgentRunService.detail(runId));
    }

    @GetMapping("/{runId}/events")
    public ApiResponse<List<AgentRunEventResponse>> events(@PathVariable Long runId,
                                                           @RequestParam(required = false) Long afterEventId,
                                                           @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(adminAgentRunService.listEvents(runId, afterEventId, pageSize));
    }

    @PostMapping("/{runId}/cancel")
    public ApiResponse<AgentRunResponse> cancel(@PathVariable Long runId) {
        return ApiResponse.success(adminAgentRunService.cancel(runId));
    }

    @GetMapping("/{runId}/audit")
    public ApiResponse<AgentRunAuditResponse> audit(@PathVariable Long runId) {
        return ApiResponse.success(agentAuditService.audit(runId));
    }

    @GetMapping("/{runId}/model-requests")
    public ApiResponse<List<AgentModelRequestSnapshotResponse>> modelRequests(
            @PathVariable Long runId,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(agentAuditService.modelRequests(runId, afterId, pageSize));
    }

    @PutMapping("/{runId}/audit-review")
    public ApiResponse<AgentRunAuditReviewResponse> review(@PathVariable Long runId,
                                                           @Valid @RequestBody UpdateAgentRunAuditReviewRequest request) {
        return ApiResponse.success(agentAuditService.review(runId, AuthContext.get().userId(), request));
    }
}
