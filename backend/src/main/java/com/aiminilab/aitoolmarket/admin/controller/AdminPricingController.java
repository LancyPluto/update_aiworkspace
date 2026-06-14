package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.dto.PricingMarginUpsertRequest;
import com.aiminilab.aitoolmarket.admin.dto.PricingRuleUpsertRequest;
import com.aiminilab.aitoolmarket.admin.service.PricingConfigAdminService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.credit.entity.PricingMargin;
import com.aiminilab.aitoolmarket.credit.entity.PricingRule;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/pricing")
public class AdminPricingController {

    private final PricingConfigAdminService pricingConfigAdminService;

    public AdminPricingController(PricingConfigAdminService pricingConfigAdminService) {
        this.pricingConfigAdminService = pricingConfigAdminService;
    }

    @GetMapping("/margins")
    public ApiResponse<List<PricingMargin>> listMargins() {
        return ApiResponse.success(pricingConfigAdminService.listMargins());
    }

    @PostMapping("/margins")
    public ApiResponse<PricingMargin> saveMargin(@Valid @RequestBody PricingMarginUpsertRequest request) {
        return ApiResponse.success(pricingConfigAdminService.saveMargin(request));
    }

    @DeleteMapping("/margins/{id}")
    public ApiResponse<Void> deleteMargin(@PathVariable Long id) {
        pricingConfigAdminService.deleteMargin(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/rules")
    public ApiResponse<List<PricingRule>> listRules() {
        return ApiResponse.success(pricingConfigAdminService.listRules());
    }

    @PostMapping("/rules")
    public ApiResponse<PricingRule> saveRule(@Valid @RequestBody PricingRuleUpsertRequest request) {
        return ApiResponse.success(pricingConfigAdminService.saveRule(request));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        pricingConfigAdminService.deleteRule(id);
        return ApiResponse.success(null);
    }
}
