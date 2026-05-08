package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ToolController {

    private final ToolService toolService;

    public ToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/tool-categories")
    public ApiResponse<List<ToolCategoryResponse>> categories() {
        return ApiResponse.success(toolService.categories());
    }

    @GetMapping("/tools")
    public ApiResponse<PageResponse<ToolSummaryResponse>> tools() {
        return ApiResponse.success(toolService.userTools());
    }

    @GetMapping("/tools/{toolCode}")
    public ApiResponse<ToolDetailResponse> detail(@PathVariable String toolCode) {
        return ApiResponse.success(toolService.userToolDetail(toolCode));
    }
}
