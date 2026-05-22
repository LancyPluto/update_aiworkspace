package com.aiminilab.aitoolmarket.market.support;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.market.dto.CapabilityDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class CapabilitiesCodec {

    private static final TypeReference<List<CapabilityDto>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public CapabilitiesCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(List<CapabilityDto> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "capabilities 序列化失败");
        }
    }

    public List<CapabilityDto> parse(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<CapabilityDto> list = objectMapper.readValue(json, LIST_TYPE);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "capabilities 格式无效");
        }
    }
}
