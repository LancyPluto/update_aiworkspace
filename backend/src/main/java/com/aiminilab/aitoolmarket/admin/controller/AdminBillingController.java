package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/v1/billing")
public class AdminBillingController {

    private final BillingService billingService;

    public AdminBillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/overview")
    public ApiResponse<BillingOverviewResponse> overview(@RequestParam(required = false) Long userId,
                                                         @RequestParam(required = false) Long modelConfigId,
                                                         @RequestParam(required = false) String provider,
                                                         @RequestParam(required = false) String modelName,
                                                         @RequestParam(required = false) String sourceType,
                                                         @RequestParam(required = false) Long sourceId,
                                                         @RequestParam(required = false) LocalDate startDate,
                                                         @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.success(billingService.overview(userId, modelConfigId, provider, modelName, sourceType,
                sourceId, startDate, endDate));
    }

    @GetMapping("/usage-logs")
    public ApiResponse<PageResponse<BillingUsageLogResponse>> logs(@RequestParam(required = false) Integer pageNo,
                                                                   @RequestParam(required = false) Integer pageSize,
                                                                   @RequestParam(required = false) Long userId,
                                                                   @RequestParam(required = false) Long modelConfigId,
                                                                   @RequestParam(required = false) String provider,
                                                                   @RequestParam(required = false) String modelName,
                                                                   @RequestParam(required = false) String sourceType,
                                                                   @RequestParam(required = false) Long sourceId,
                                                                   @RequestParam(required = false) LocalDate startDate,
                                                                   @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.success(billingService.logs(pageNo, pageSize, userId, modelConfigId, provider, modelName,
                sourceType, sourceId, startDate, endDate));
    }
}
