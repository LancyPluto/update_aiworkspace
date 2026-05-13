package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.dto.UpdateSettingsRequest;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/settings")
public class AdminSettingController {

    private final SystemSettingService systemSettingService;

    public AdminSettingController(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> settings() {
        return ApiResponse.success(systemSettingService.settings());
    }

    @PutMapping
    public ApiResponse<Map<String, String>> update(@Valid @RequestBody UpdateSettingsRequest request) {
        return ApiResponse.success(systemSettingService.updateSettings(request.settings()));
    }
}
