package com.aiminilab.aitoolmarket.market.service;

import com.aiminilab.aitoolmarket.market.dto.CreateSessionRequest;
import com.aiminilab.aitoolmarket.market.dto.SessionResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketSession;

import java.util.List;

public interface AiMarketSessionService {

    List<SessionResponse> list(Long userId, String toolId);

    SessionResponse create(Long userId, CreateSessionRequest request);

    void delete(Long userId, String sessionId);

    AiMarketSession requireOwnedSession(Long userId, String sessionId, String toolId);
}
