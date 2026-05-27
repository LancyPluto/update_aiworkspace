package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.dto.UpsertAiToolRequest;
import com.aiminilab.aitoolmarket.market.service.AdminAiMarketToolService;
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
@RequestMapping("/api/admin/ai-tools")
public class AdminAiMarketToolController {

    private final AdminAiMarketToolService adminAiMarketToolService;

    public AdminAiMarketToolController(AdminAiMarketToolService adminAiMarketToolService) {
        this.adminAiMarketToolService = adminAiMarketToolService;
    }

    @GetMapping
    public ApiResponse<List<AiToolResponse>> list() {
        return ApiResponse.success(adminAiMarketToolService.listAll());
    }

    @PostMapping
    public ApiResponse<AiToolResponse> create(@Valid @RequestBody UpsertAiToolRequest request) {
        return ApiResponse.success(adminAiMarketToolService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiToolResponse> update(@PathVariable String id, @Valid @RequestBody UpsertAiToolRequest request) {
        return ApiResponse.success(adminAiMarketToolService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        adminAiMarketToolService.delete(id);
        return ApiResponse.success(null);
    }
}
