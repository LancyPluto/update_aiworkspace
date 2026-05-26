package com.aiminilab.aitoolmarket.market.service;

import com.aiminilab.aitoolmarket.market.dto.MessageResponse;

import java.util.List;

public interface AiMarketMessageService {

    List<MessageResponse> list(Long userId, String sessionId);
}
