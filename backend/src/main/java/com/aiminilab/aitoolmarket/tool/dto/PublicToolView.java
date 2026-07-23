package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

import java.util.Locale;

public enum PublicToolView {
    SUMMARY,
    COMPACT;

    public static PublicToolView from(String value) {
        if (value == null || value.isBlank()) {
            return SUMMARY;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "summary" -> SUMMARY;
            case "compact" -> COMPACT;
            default -> throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "view must be one of: summary, compact"
            );
        };
    }

    public String cacheKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
