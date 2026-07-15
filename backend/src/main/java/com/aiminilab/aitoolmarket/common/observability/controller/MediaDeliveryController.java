package com.aiminilab.aitoolmarket.common.observability.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.observability.dto.MediaDeliveryEventBatchRequest;
import com.aiminilab.aitoolmarket.common.observability.metrics.MediaDeliveryMetrics;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/observability")
public class MediaDeliveryController {
    private final MediaDeliveryMetrics metrics;

    public MediaDeliveryController(MediaDeliveryMetrics metrics) {
        this.metrics = metrics;
    }

    @PostMapping("/media-events")
    public ApiResponse<Void> record(@Valid @RequestBody MediaDeliveryEventBatchRequest request) {
        request.getEvents().forEach(metrics::recordClientEvent);
        return ApiResponse.success(null);
    }
}
