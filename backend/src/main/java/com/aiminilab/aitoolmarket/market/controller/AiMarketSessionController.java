package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.CreateSessionRequest;
import com.aiminilab.aitoolmarket.market.dto.SessionResponse;
import com.aiminilab.aitoolmarket.market.service.AiMarketSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sessions")
public class AiMarketSessionController {

    private final AiMarketSessionService aiMarketSessionService;

    public AiMarketSessionController(AiMarketSessionService aiMarketSessionService) {
        this.aiMarketSessionService = aiMarketSessionService;
    }

    @GetMapping
    public ApiResponse<List<SessionResponse>> list(@RequestParam String toolId) {
        return ApiResponse.success(aiMarketSessionService.list(AuthContext.get().userId(), toolId));
    }

    @PostMapping
    public ApiResponse<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
        return ApiResponse.success(aiMarketSessionService.create(AuthContext.get().userId(), request));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable String sessionId) {
        aiMarketSessionService.delete(AuthContext.get().userId(), sessionId);
        return ApiResponse.success(null);
    }
}
