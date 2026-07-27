package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

/** A transport failure classified by whether a mutating request may have arrived. */
public final class PptEngineTransportException extends BusinessException {
    private final boolean outcomeUnknown;

    public PptEngineTransportException(String message, boolean outcomeUnknown) {
        super(ErrorCode.PPT_ENGINE_ERROR, message);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
