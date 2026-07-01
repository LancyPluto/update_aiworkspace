package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.dto.UpsertAiToolRequest;
import com.aiminilab.aitoolmarket.market.service.AdminAiMarketToolService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping("/api/admin/ai-tools")
public class AdminAiMarketToolController {

    private static final Logger log = LoggerFactory.getLogger(AdminAiMarketToolController.class);

    private final AdminAiMarketToolService adminAiMarketToolService;

    public AdminAiMarketToolController(AdminAiMarketToolService adminAiMarketToolService) {
        this.adminAiMarketToolService = adminAiMarketToolService;
    }

    @GetMapping
    public ApiResponse<List<AiToolResponse>> list() {
        log.warn("Deprecated API used: GET /api/admin/ai-tools. Use /api/admin/v1/tools instead.");
        return ApiResponse.success(adminAiMarketToolService.listAll());
    }

    @PostMapping
    public ApiResponse<AiToolResponse> create(@Valid @RequestBody UpsertAiToolRequest request) {
        log.warn("Deprecated API used: POST /api/admin/ai-tools. New code must use /api/admin/v1/tools.");
        return ApiResponse.success(adminAiMarketToolService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiToolResponse> update(@PathVariable String id, @Valid @RequestBody UpsertAiToolRequest request) {
        log.warn("Deprecated API used: PUT /api/admin/ai-tools/{}. New code must use /api/admin/v1/tools.", id);
        return ApiResponse.success(adminAiMarketToolService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        log.warn("Deprecated API used: DELETE /api/admin/ai-tools/{}. New code must use /api/admin/v1/tools.", id);
        adminAiMarketToolService.delete(id);
        return ApiResponse.success(null);
    }
}
