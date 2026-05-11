package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateCategoryStatusRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolCategoryRequest;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/tool-categories")
public class AdminToolCategoryController {

    private final ToolService toolService;

    public AdminToolCategoryController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping
    public ApiResponse<List<ToolCategoryResponse>> list() {
        return ApiResponse.success(toolService.adminCategories());
    }

    @PostMapping
    public ApiResponse<ToolCategoryResponse> create(@Valid @RequestBody UpsertToolCategoryRequest request) {
        return ApiResponse.success(toolService.createCategory(request));
    }

    @PutMapping("/{categoryId}")
    public ApiResponse<ToolCategoryResponse> update(@PathVariable Long categoryId,
                                                    @Valid @RequestBody UpsertToolCategoryRequest request) {
        return ApiResponse.success(toolService.updateCategory(categoryId, request));
    }

    @PatchMapping("/{categoryId}/status")
    public ApiResponse<ToolCategoryResponse> updateStatus(@PathVariable Long categoryId,
                                                          @Valid @RequestBody UpdateCategoryStatusRequest request) {
        return ApiResponse.success(toolService.updateCategoryStatus(categoryId, request.status()));
    }
}
