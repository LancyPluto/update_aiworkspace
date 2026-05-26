package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.MessageResponse;
import com.aiminilab.aitoolmarket.market.service.AiMarketMessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/messages")
public class AiMarketMessageController {

    private final AiMarketMessageService aiMarketMessageService;

    public AiMarketMessageController(AiMarketMessageService aiMarketMessageService) {
        this.aiMarketMessageService = aiMarketMessageService;
    }

    @GetMapping
    public ApiResponse<List<MessageResponse>> list(@RequestParam String sessionId) {
        return ApiResponse.success(aiMarketMessageService.list(AuthContext.get().userId(), sessionId));
    }
}
