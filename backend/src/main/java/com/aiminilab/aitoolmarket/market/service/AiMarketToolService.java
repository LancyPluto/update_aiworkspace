package com.aiminilab.aitoolmarket.market.service;

import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;

import java.util.List;

public interface AiMarketToolService {

    List<AiToolResponse> listEnabled();

    AiToolResponse getEnabledDetail(String toolId);

    AiMarketTool requireTool(String toolId);

    AiMarketTool requireEnabledToolForNewSession(String toolId);

    AiMarketTool requireToolForChat(String toolId);
}
