package com.aiminilab.aitoolmarket.common.observability.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.observability.dto.WebVitalMetricRequest;
import com.aiminilab.aitoolmarket.common.observability.service.WebVitalsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/observability")
public class WebVitalsController {
    private final WebVitalsService service;

    public WebVitalsController(WebVitalsService service) {
        this.service = service;
    }

    @PostMapping("/web-vitals")
    public ApiResponse<Void> record(@Valid @RequestBody WebVitalMetricRequest request) {
        service.record(request);
        return ApiResponse.success(null);
    }
}
