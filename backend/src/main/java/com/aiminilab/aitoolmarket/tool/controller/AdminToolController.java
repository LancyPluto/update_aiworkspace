package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaAdminResponse;
import com.aiminilab.aitoolmarket.tool.dto.ApplyToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCoverUploadResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolPromptDraftRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolPromptDraftResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.aiminilab.aitoolmarket.tool.service.ToolPromptDraftService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/tools")
public class AdminToolController {

    private final ToolService toolService;
    private final ToolPromptDraftService promptDraftService;

    public AdminToolController(ToolService toolService,
                               ToolPromptDraftService promptDraftService) {
        this.toolService = toolService;
        this.promptDraftService = promptDraftService;
    }

    @GetMapping
    public ApiResponse<PageResponse<ToolSummaryResponse>> tools(@RequestParam(required = false) String keyword,
                                                                @RequestParam(required = false) Long categoryId,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(required = false) Integer pageNo,
                                                                @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(toolService.adminTools(keyword, categoryId, status, pageNo, pageSize));
    }

    @GetMapping("/{toolId}")
    public ApiResponse<?> detail(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.adminToolDetail(toolId));
    }

    @PostMapping
    public ApiResponse<ToolSummaryResponse> create(@Valid @RequestBody UpsertToolRequest request) {
        return ApiResponse.success(toolService.createTool(request, AuthContext.get().userId()));
    }

    @PostMapping("/cover-upload")
    public ApiResponse<ToolCoverUploadResponse> uploadCover(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(required = false) String toolName,
                                                            @RequestParam(required = false) String toolCode,
                                                            @RequestParam(required = false) String modelName) {
        return ApiResponse.success(toolService.uploadToolCover(file, toolName, toolCode, modelName));
    }

    @PostMapping("/prompt-draft")
    public ApiResponse<ToolPromptDraftResponse> promptDraft(@RequestBody ToolPromptDraftRequest request) {
        return ApiResponse.success(promptDraftService.generate(request));
    }

    @PutMapping("/{toolId}")
    public ApiResponse<ToolSummaryResponse> update(@PathVariable Long toolId, @Valid @RequestBody UpsertToolRequest request) {
        return ApiResponse.success(toolService.updateTool(toolId, request, AuthContext.get().userId()));
    }

    @DeleteMapping("/{toolId}")
    public ApiResponse<Void> delete(@PathVariable Long toolId) {
        toolService.deleteTool(toolId, AuthContext.get().userId());
        return ApiResponse.success(null);
    }

    @PostMapping("/{toolId}/publish")
    public ApiResponse<ToolSummaryResponse> publish(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.publishTool(toolId, AuthContext.get().userId()));
    }

    @PostMapping("/{toolId}/offline")
    public ApiResponse<ToolSummaryResponse> offline(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.offlineTool(toolId, AuthContext.get().userId()));
    }

    @GetMapping("/{toolId}/field-schemas")
    public ApiResponse<List<FieldSchemaAdminResponse>> fieldSchemas(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.adminFieldSchemas(toolId));
    }

    @PostMapping("/{toolId}/field-schemas")
    public ApiResponse<FieldSchemaAdminResponse> upsertFieldSchema(@PathVariable Long toolId,
                                                                   @Valid @RequestBody UpsertFieldSchemaRequest request) {
        return ApiResponse.success(toolService.upsertActiveFieldSchema(toolId, request, AuthContext.get().userId()));
    }

    @GetMapping("/{toolId}/fields")
    public ApiResponse<List<ToolFieldResponse>> fields(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.adminFields(toolId));
    }

    @PutMapping("/{toolId}/fields")
    public ApiResponse<List<ToolFieldResponse>> updateFields(@PathVariable Long toolId,
                                                             @Valid @RequestBody UpdateToolFieldsRequest request) {
        return ApiResponse.success(toolService.updateFields(toolId, request));
    }

    @PostMapping("/{toolId}/apply-template")
    public ApiResponse<Void> applyTemplate(@PathVariable Long toolId,
                                           @Valid @RequestBody ApplyToolTemplateRequest request) {
        toolService.applyTemplate(toolId, request, AuthContext.get().userId());
        return ApiResponse.success(null);
    }
}
