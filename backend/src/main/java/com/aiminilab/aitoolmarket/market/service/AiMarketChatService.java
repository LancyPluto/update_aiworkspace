package com.aiminilab.aitoolmarket.market.service;

import com.aiminilab.aitoolmarket.market.dto.ChatMessageRequest;
import com.aiminilab.aitoolmarket.market.dto.ChatMessageResponse;

public interface AiMarketChatService {

    ChatMessageResponse send(Long userId, ChatMessageRequest request);
}
