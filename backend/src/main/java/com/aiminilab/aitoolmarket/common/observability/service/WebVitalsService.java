package com.aiminilab.aitoolmarket.common.observability.service;

import com.aiminilab.aitoolmarket.common.observability.dto.WebVitalMetricRequest;

public interface WebVitalsService {
    void record(WebVitalMetricRequest request);
}
