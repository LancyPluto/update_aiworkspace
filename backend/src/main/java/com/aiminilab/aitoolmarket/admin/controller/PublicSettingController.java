package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceSettingsResponse;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class PublicSettingController {

    private final SystemSettingService systemSettingService;

    public PublicSettingController(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @GetMapping("/customer-service")
    public ApiResponse<CustomerServiceSettingsResponse> customerService() {
        return ApiResponse.success(CustomerServiceSettingsResponse.from(systemSettingService.settings()));
    }
}
