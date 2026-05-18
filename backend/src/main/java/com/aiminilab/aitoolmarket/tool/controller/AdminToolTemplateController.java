package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolTemplateDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolTemplateSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.service.ToolTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/tool-templates")
public class AdminToolTemplateController {

    private final ToolTemplateService toolTemplateService;

    public AdminToolTemplateController(ToolTemplateService toolTemplateService) {
        this.toolTemplateService = toolTemplateService;
    }

    @GetMapping
    public ApiResponse<List<ToolTemplateSummaryResponse>> list(
            @RequestParam(required = false) String toolType,
            @RequestParam(required = false) String executionHandler,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(toolTemplateService.list(toolType, executionHandler, includeInactive));
    }

    @GetMapping("/{templateCode}")
    public ApiResponse<ToolTemplateDetailResponse> detail(@PathVariable String templateCode) {
        return ApiResponse.success(toolTemplateService.detailByCode(templateCode));
    }

    @PostMapping
    public ApiResponse<ToolTemplateDetailResponse> create(@Valid @RequestBody UpsertToolTemplateRequest request) {
        return ApiResponse.success(toolTemplateService.create(request));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<ToolTemplateDetailResponse> update(@PathVariable Long templateId,
                                                          @Valid @RequestBody UpsertToolTemplateRequest request) {
        return ApiResponse.success(toolTemplateService.update(templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(@PathVariable Long templateId) {
        toolTemplateService.delete(templateId);
        return ApiResponse.success(null);
    }
}
