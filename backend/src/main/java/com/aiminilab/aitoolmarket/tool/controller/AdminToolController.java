package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.CreateFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptVersionRequest;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptVersionResponse;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateRequest;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
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

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public class AdminToolController {

    private final ToolService toolService;

    public AdminToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/tools")
    public ApiResponse<PageResponse<ToolSummaryResponse>> tools() {
        return ApiResponse.success(toolService.adminTools());
    }

    @PostMapping("/tools")
    public ApiResponse<ToolSummaryResponse> create(@Valid @RequestBody UpsertToolRequest request) {
        return ApiResponse.success(toolService.createTool(request, AuthContext.get().userId()));
    }

    @PutMapping("/tools/{toolId}")
    public ApiResponse<ToolSummaryResponse> update(@PathVariable Long toolId, @Valid @RequestBody UpsertToolRequest request) {
        return ApiResponse.success(toolService.updateTool(toolId, request, AuthContext.get().userId()));
    }

    @PostMapping("/tools/{toolId}/publish")
    public ApiResponse<ToolSummaryResponse> publish(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.publishTool(toolId, AuthContext.get().userId()));
    }

    @PostMapping("/tools/{toolId}/offline")
    public ApiResponse<ToolSummaryResponse> offline(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.offlineTool(toolId, AuthContext.get().userId()));
    }

    @GetMapping("/tools/{toolId}/fields")
    public ApiResponse<List<ToolFieldResponse>> fields(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.adminFields(toolId));
    }

    @PutMapping("/tools/{toolId}/fields")
    public ApiResponse<List<ToolFieldResponse>> updateFields(@PathVariable Long toolId,
                                                             @Valid @RequestBody UpdateToolFieldsRequest request) {
        return ApiResponse.success(toolService.updateFields(toolId, request));
    }

    @GetMapping("/tools/{toolId}/field-schemas")
    public ApiResponse<List<FieldSchemaResponse>> fieldSchemas(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.fieldSchemas(toolId));
    }

    @PostMapping("/tools/{toolId}/field-schemas")
    public ApiResponse<FieldSchemaResponse> createFieldSchema(@PathVariable Long toolId,
                                                              @Valid @RequestBody CreateFieldSchemaRequest request) {
        return ApiResponse.success(toolService.createFieldSchema(toolId, request, AuthContext.get().userId()));
    }

    @PostMapping("/field-schemas/{schemaId}/publish")
    public ApiResponse<FieldSchemaResponse> publishFieldSchema(@PathVariable Long schemaId) {
        return ApiResponse.success(toolService.publishFieldSchema(schemaId));
    }

    @GetMapping("/tools/{toolId}/prompts")
    public ApiResponse<List<PromptResponse>> prompts(@PathVariable Long toolId) {
        return ApiResponse.success(toolService.prompts(toolId));
    }

    @PostMapping("/tools/{toolId}/prompts")
    public ApiResponse<PromptResponse> createPrompt(@PathVariable Long toolId,
                                                    @Valid @RequestBody CreatePromptRequest request) {
        return ApiResponse.success(toolService.createPrompt(toolId, request));
    }

    @GetMapping("/prompts/{promptId}/versions")
    public ApiResponse<List<PromptVersionResponse>> promptVersions(@PathVariable Long promptId) {
        return ApiResponse.success(toolService.promptVersions(promptId));
    }

    @PostMapping("/prompts/{promptId}/versions")
    public ApiResponse<PromptVersionResponse> createPromptVersion(@PathVariable Long promptId,
                                                                  @Valid @RequestBody CreatePromptVersionRequest request) {
        return ApiResponse.success(toolService.createPromptVersion(promptId, request, AuthContext.get().userId()));
    }

    @PostMapping("/prompt-versions/{promptVersionId}/test-generate")
    public ApiResponse<TestGenerateResponse> testGenerate(@PathVariable Long promptVersionId,
                                                          @Valid @RequestBody TestGenerateRequest request) {
        return ApiResponse.success(toolService.testGenerate(promptVersionId, request));
    }

    @PostMapping("/prompt-versions/{promptVersionId}/publish")
    public ApiResponse<PromptVersionResponse> publishPromptVersion(@PathVariable Long promptVersionId) {
        return ApiResponse.success(toolService.publishPromptVersion(promptVersionId));
    }
}
