package com.aiminilab.aitoolmarket.common.observability.service.impl;

import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.observability.dto.WebVitalMetricRequest;
import com.aiminilab.aitoolmarket.common.observability.metrics.WebVitalsMetrics;
import com.aiminilab.aitoolmarket.common.observability.service.WebVitalsService;
import org.springframework.stereotype.Service;

@Service
public class WebVitalsServiceImpl implements WebVitalsService {
    private final WebVitalsMetrics metrics;

    public WebVitalsServiceImpl(WebVitalsMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void record(WebVitalMetricRequest request) {
        if (request == null || !metrics.isAllowedName(request.getName())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Unsupported web vital metric");
        }
        metrics.record(request.getName(), request.getRating(), request.getPage(), request.getNavigationType(), request.getValue());
    }
}
