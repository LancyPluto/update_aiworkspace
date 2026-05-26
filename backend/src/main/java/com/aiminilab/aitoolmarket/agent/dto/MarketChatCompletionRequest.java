package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record MarketChatCompletionRequest(
        AgentModelConfigRequest modelConfig,
        List<MarketChatMessageDto> messages
) {
}
