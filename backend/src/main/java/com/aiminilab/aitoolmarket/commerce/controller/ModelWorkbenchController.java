package com.aiminilab.aitoolmarket.commerce.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.commerce.dto.CreateChatSessionRequest;
import com.aiminilab.aitoolmarket.commerce.dto.ReportModelIssueRequest;
import com.aiminilab.aitoolmarket.commerce.dto.SendModelMessageRequest;
import com.aiminilab.aitoolmarket.commerce.service.CommercePlatformService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/model-workbench")
public class ModelWorkbenchController {

    private final CommercePlatformService service;

    public ModelWorkbenchController(CommercePlatformService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans(@RequestParam(required = false) String planType) {
        return ApiResponse.success(service.listPlans(planType));
    }

    @GetMapping("/subscriptions")
    public ApiResponse<List<Map<String, Object>>> subscriptions() {
        return ApiResponse.success(service.listUserSubscriptions(AuthContext.get().userId()));
    }

    @GetMapping("/pools")
    public ApiResponse<List<Map<String, Object>>> pools() {
        return ApiResponse.success(service.listPoolsForUser(AuthContext.get().userId()));
    }

    @GetMapping("/pools/{poolId}/nodes")
    public ApiResponse<List<Map<String, Object>>> nodes(@PathVariable Long poolId) {
        return ApiResponse.success(service.listNodesForUser(AuthContext.get().userId(), poolId));
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(@RequestBody CreateChatSessionRequest request) {
        return ApiResponse.success(service.createChatSession(AuthContext.get().userId(), request));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> sessions() {
        return ApiResponse.success(service.listChatSessions(AuthContext.get().userId()));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<Map<String, Object>>> messages(@PathVariable Long sessionId) {
        return ApiResponse.success(service.listMessages(AuthContext.get().userId(), sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> send(@PathVariable Long sessionId,
                                                 @Valid @RequestBody SendModelMessageRequest request) {
        return ApiResponse.success(service.sendMessage(AuthContext.get().userId(), sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/messages/stream")
    public SseEmitter stream(@PathVariable Long sessionId,
                             @Valid @RequestBody SendModelMessageRequest request) {
        return service.streamMessage(AuthContext.get().userId(), sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/feedback")
    public ApiResponse<Void> feedback(@PathVariable Long sessionId,
                                      @Valid @RequestBody ReportModelIssueRequest request) {
        service.reportModelIssue(AuthContext.get().userId(), sessionId, request);
        return ApiResponse.success(null);
    }
}
