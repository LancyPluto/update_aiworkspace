package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiOverviewResponse;
import com.aiminilab.aitoolmarket.agent.service.UnifiedApiOverviewService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/unified-api")
public class AdminUnifiedApiController {

    private final UnifiedApiOverviewService unifiedApiOverviewService;

    public AdminUnifiedApiController(UnifiedApiOverviewService unifiedApiOverviewService) {
        this.unifiedApiOverviewService = unifiedApiOverviewService;
    }

    @GetMapping("/overview")
    public ApiResponse<UnifiedApiOverviewResponse> overview() {
        return ApiResponse.success(unifiedApiOverviewService.overview());
    }
}
