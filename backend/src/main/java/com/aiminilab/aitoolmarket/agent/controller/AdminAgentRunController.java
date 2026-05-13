package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunDetailResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.service.AdminAgentRunService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/agent/runs")
public class AdminAgentRunController {

    private final AdminAgentRunService adminAgentRunService;

    public AdminAgentRunController(AdminAgentRunService adminAgentRunService) {
        this.adminAgentRunService = adminAgentRunService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminAgentRunListItemResponse>> list(@RequestParam(required = false) String status,
                                                                         @RequestParam(required = false) Long userId,
                                                                         @RequestParam(required = false) Integer pageNo,
                                                                         @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(adminAgentRunService.list(status, userId, pageNo, pageSize));
    }

    @GetMapping("/stats")
    public ApiResponse<AdminAgentRunStatsResponse> stats() {
        return ApiResponse.success(adminAgentRunService.stats());
    }

    @GetMapping("/{runId}")
    public ApiResponse<AdminAgentRunDetailResponse> detail(@PathVariable Long runId) {
        return ApiResponse.success(adminAgentRunService.detail(runId));
    }

    @PostMapping("/{runId}/cancel")
    public ApiResponse<AgentRunResponse> cancel(@PathVariable Long runId) {
        return ApiResponse.success(adminAgentRunService.cancel(runId));
    }
}
