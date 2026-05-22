package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketToolMapper;
import com.aiminilab.aitoolmarket.market.service.AiMarketToolService;
import com.aiminilab.aitoolmarket.market.support.CapabilitiesCodec;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiMarketToolServiceImpl implements AiMarketToolService {

    private final AiMarketToolMapper aiMarketToolMapper;
    private final CapabilitiesCodec capabilitiesCodec;

    public AiMarketToolServiceImpl(AiMarketToolMapper aiMarketToolMapper, CapabilitiesCodec capabilitiesCodec) {
        this.aiMarketToolMapper = aiMarketToolMapper;
        this.capabilitiesCodec = capabilitiesCodec;
    }

    @Override
    public List<AiToolResponse> listEnabled() {
        return aiMarketToolMapper.findAllEnabled().stream()
                .map(tool -> AiToolResponse.from(tool, capabilitiesCodec))
                .toList();
    }

    @Override
    public AiToolResponse getEnabledDetail(String toolId) {
        AiMarketTool tool = requireTool(toolId);
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new BusinessException(ErrorCode.TOOL_OFFLINE, "工具已下架");
        }
        return AiToolResponse.from(tool, capabilitiesCodec);
    }

    @Override
    public AiMarketTool requireTool(String toolId) {
        return aiMarketToolMapper.findOptionalByToolId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    @Override
    public AiMarketTool requireEnabledToolForNewSession(String toolId) {
        AiMarketTool tool = requireTool(toolId);
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new BusinessException(ErrorCode.TOOL_OFFLINE, "工具已下架，无法新建会话");
        }
        return tool;
    }

    @Override
    public AiMarketTool requireToolForChat(String toolId) {
        return requireTool(toolId);
    }
}
