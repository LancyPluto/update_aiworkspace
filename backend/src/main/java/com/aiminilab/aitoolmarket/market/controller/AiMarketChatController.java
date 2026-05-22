package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.ChatMessageRequest;
import com.aiminilab.aitoolmarket.market.dto.ChatMessageResponse;
import com.aiminilab.aitoolmarket.market.service.AiMarketChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/messages")
public class AiMarketChatController {

    private final AiMarketChatService aiMarketChatService;

    public AiMarketChatController(AiMarketChatService aiMarketChatService) {
        this.aiMarketChatService = aiMarketChatService;
    }

    @PostMapping
    public ApiResponse<ChatMessageResponse> send(@Valid @RequestBody ChatMessageRequest request) {
        return ApiResponse.success(aiMarketChatService.send(AuthContext.get().userId(), request));
    }
}
