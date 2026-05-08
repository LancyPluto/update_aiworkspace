package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/tools")
public class AdminToolController {

    private final ToolService toolService;

    public AdminToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping
    public ApiResponse<PageResponse<ToolSummaryResponse>> tools() {
        return ApiResponse.success(toolService.adminTools());
    }

    @PostMapping
    public ApiResponse<ToolSummaryResponse> create(@Valid @RequestBody UpsertToolRequest request) {
        return ApiResponse.success(toolService.createTool(request, AuthContext.get().userId()));
    }

    @PutMapping("/{toolId}")
    public ApiResponse<ToolSummaryResponse> update(@PathVariable Long toolId, @Valid @RequestBody UpsertToolRequest request) {
        return ApiResponse.success(toolService.updateTool(toolId, request, AuthContext.get().userId()));
    }

    @PostMapping("/{toolId}/publish")
    public ApiResponse<ToolSummaryResponse> publish(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.publishTool(toolId, AuthContext.get().userId()));
    }

    @PostMapping("/{toolId}/offline")
    public ApiResponse<ToolSummaryResponse> offline(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.offlineTool(toolId, AuthContext.get().userId()));
    }
}
