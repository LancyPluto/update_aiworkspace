package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.service.AiMarketToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai-tools")
public class AiMarketToolController {

    private final AiMarketToolService aiMarketToolService;

    public AiMarketToolController(AiMarketToolService aiMarketToolService) {
        this.aiMarketToolService = aiMarketToolService;
    }

    @GetMapping
    public ApiResponse<List<AiToolResponse>> listEnabled() {
        return ApiResponse.success(aiMarketToolService.listEnabled());
    }

    @GetMapping("/{toolId}")
    public ApiResponse<AiToolResponse> detail(@PathVariable String toolId) {
        return ApiResponse.success(aiMarketToolService.getEnabledDetail(toolId));
    }
}
