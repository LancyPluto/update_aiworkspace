package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.ModelAccountRoutingRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.service.ModelAccountRoutingService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/model-vendor-accounts")
public class AdminModelAccountRoutingController {
    private final ModelAccountRoutingService routingService;

    public AdminModelAccountRoutingController(ModelAccountRoutingService routingService) {
        this.routingService = routingService;
    }

    @PatchMapping("/{id}/routing")
    public ApiResponse<ModelVendorAccountResponse> updateRouting(
            @PathVariable Long id,
            @Valid @RequestBody ModelAccountRoutingRequest request) {
        return ApiResponse.success(routingService.update(id, request));
    }
}
