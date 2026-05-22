package com.aiminilab.aitoolmarket.market.dto;

import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.aiminilab.aitoolmarket.market.support.CapabilitiesCodec;

import java.util.List;

public record AiToolResponse(
        String id,
        String name,
        String iconUrl,
        String description,
        boolean enabled,
        int order,
        String primaryColor,
        String welcomeMessage,
        List<CapabilityDto> capabilities
) {
    public static AiToolResponse from(AiMarketTool tool, CapabilitiesCodec codec) {
        return new AiToolResponse(
                tool.getToolId(),
                tool.getName(),
                tool.getIconUrl(),
                tool.getDescription(),
                Boolean.TRUE.equals(tool.getEnabled()),
                tool.getSortOrder() == null ? 0 : tool.getSortOrder(),
                tool.getPrimaryColor(),
                tool.getWelcomeMessage(),
                codec.parse(tool.getCapabilitiesJson())
        );
    }
}
