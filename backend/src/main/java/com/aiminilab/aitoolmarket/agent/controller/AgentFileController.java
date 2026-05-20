package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentFileResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentFileService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/agent/sessions/{sessionId}/files")
public class AgentFileController {

    private final AgentFileService agentFileService;

    public AgentFileController(AgentFileService agentFileService) {
        this.agentFileService = agentFileService;
    }

    @PostMapping
    public ApiResponse<AgentFileResponse> upload(@PathVariable Long sessionId,
                                                 @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(agentFileService.upload(AuthContext.get().userId(), sessionId, file));
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentFileResponse>> list(@PathVariable Long sessionId) {
        return ApiResponse.success(agentFileService.list(AuthContext.get().userId(), sessionId));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(@PathVariable Long sessionId, @PathVariable Long fileId) {
        agentFileService.delete(AuthContext.get().userId(), sessionId, fileId);
        return ApiResponse.success(null);
    }
}
