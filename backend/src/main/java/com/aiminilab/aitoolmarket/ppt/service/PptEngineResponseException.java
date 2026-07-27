package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

/**
 * The PPT engine returned a definitive HTTP response. Unlike transport failures,
 * this outcome is known and must not be left in reconciliation.
 */
public class PptEngineResponseException extends BusinessException {
    private final int statusCode;
    private final boolean retryable;

    public PptEngineResponseException(int statusCode, String message) {
        super(ErrorCode.PPT_ENGINE_ERROR, message);
        this.statusCode = statusCode;
        this.retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getFailureCode() {
        if (statusCode == 429) {
            return "PPT_ENGINE_BUSY";
        }
        if (statusCode >= 500 || statusCode == 408) {
            return "PPT_ENGINE_UNAVAILABLE";
        }
        return "PPT_ENGINE_REQUEST_REJECTED";
    }
}
