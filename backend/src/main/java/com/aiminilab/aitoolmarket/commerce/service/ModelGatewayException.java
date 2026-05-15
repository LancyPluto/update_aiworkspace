package com.aiminilab.aitoolmarket.commerce.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

public class ModelGatewayException extends BusinessException {

    private final String errorCategory;
    private final boolean fallbackable;

    public ModelGatewayException(String errorCategory, boolean fallbackable, String message) {
        super(ErrorCode.MODEL_CALL_FAILED, message);
        this.errorCategory = errorCategory;
        this.fallbackable = fallbackable;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    public boolean isFallbackable() {
        return fallbackable;
    }
}
