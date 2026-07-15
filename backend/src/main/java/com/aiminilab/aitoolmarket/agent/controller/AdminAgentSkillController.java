package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.AgentSkillBundleResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentSkillBundleRequest;
import com.aiminilab.aitoolmarket.agent.service.AgentSkillBundleService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.aiminilab.aitoolmarket.agent.service.AgentAuditService;
import com.aiminilab.aitoolmarket.agent.dto.AgentSkillCoverageResponse;

@RestController
@RequestMapping("/api/admin/v1/agent/skills")
public class AdminAgentSkillController {
    private final AgentSkillBundleService skillBundleService;
    private final AgentAuditService auditService;

    public AdminAgentSkillController(AgentSkillBundleService skillBundleService, AgentAuditService auditService) {
        this.skillBundleService = skillBundleService;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<List<AgentSkillBundleResponse>> list() {
        return ApiResponse.success(skillBundleService.listAdmin());
    }

    @GetMapping("/coverage")
    public ApiResponse<List<AgentSkillCoverageResponse>> coverage() {
        return ApiResponse.success(auditService.skillCoverage());
    }

    @GetMapping("/{skillCode}")
    public ApiResponse<AgentSkillBundleResponse> get(@PathVariable String skillCode) {
        return ApiResponse.success(skillBundleService.getAdmin(skillCode));
    }

    @PutMapping("/{skillCode}")
    public ApiResponse<AgentSkillBundleResponse> saveDraft(@PathVariable String skillCode,
                                                           @RequestBody UpdateAgentSkillBundleRequest request) {
        return ApiResponse.success(skillBundleService.saveDraft(skillCode, request));
    }

    @PostMapping("/{skillCode}/publish")
    public ApiResponse<AgentSkillBundleResponse> publish(@PathVariable String skillCode) {
        return ApiResponse.success(skillBundleService.publish(skillCode));
    }
}
