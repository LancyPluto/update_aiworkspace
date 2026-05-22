package com.aiminilab.aitoolmarket.market.support;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.market.dto.CapabilityDto;
import com.aiminilab.aitoolmarket.market.dto.UpsertAiToolRequest;
import com.aiminilab.aitoolmarket.market.enums.CapabilityType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class AiMarketToolValidator {

    private static final Pattern TOOL_ID_PATTERN = Pattern.compile("^[a-z0-9-]{1,32}$");

    public void validateUpsert(UpsertAiToolRequest request, boolean requireId) {
        if (requireId) {
            if (request.id() == null || request.id().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "id 不能为空");
            }
            if (!TOOL_ID_PATTERN.matcher(request.id()).matches()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "id 仅支持小写字母、数字与连字符，最长 32 位");
            }
        } else if (request.id() != null && !request.id().isBlank() && !TOOL_ID_PATTERN.matcher(request.id()).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "id 仅支持小写字母、数字与连字符，最长 32 位");
        }
        if (request.order() == null || request.order() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "order 必须为正整数");
        }
        validateCapabilities(request.capabilities());
    }

    public void validateCapabilities(List<CapabilityDto> capabilities) {
        if (capabilities == null) {
            return;
        }
        for (CapabilityDto capability : capabilities) {
            if (capability == null || capability.type() == null || capability.type().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "capabilities.type 不能为空");
            }
            if (CapabilityType.parse(capability.type()).isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的 capability type: " + capability.type());
            }
        }
    }
}
