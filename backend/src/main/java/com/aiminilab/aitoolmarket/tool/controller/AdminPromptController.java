package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptVersionRequest;
import com.aiminilab.aitoolmarket.tool.dto.PromptResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptVersionResponse;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateRequest;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateResponse;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public class AdminPromptController {

    private final ToolService toolService;

    public AdminPromptController(ToolService toolService) {
        this.toolService = toolService;
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

    @PostMapping("/prompt-versions/{promptVersionId}/publish")
    public ApiResponse<PromptVersionResponse> publishPromptVersion(@PathVariable Long promptVersionId) {
        return ApiResponse.success(toolService.publishPromptVersion(promptVersionId));
    }

    @PostMapping("/prompt-versions/{promptVersionId}/test-generate")
    public ApiResponse<TestGenerateResponse> testGenerate(@PathVariable Long promptVersionId,
                                                          @Valid @RequestBody TestGenerateRequest request) {
        return ApiResponse.success(toolService.testGenerate(promptVersionId, request));
    }
}
