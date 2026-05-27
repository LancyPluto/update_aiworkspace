package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.dto.UpdateSettingsRequest;
import com.aiminilab.aitoolmarket.admin.engine.EngineApiSettingsService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/settings/engine-api")
public class AdminEngineApiSettingsController {

    private final EngineApiSettingsService engineApiSettingsService;

    public AdminEngineApiSettingsController(EngineApiSettingsService engineApiSettingsService) {
        this.engineApiSettingsService = engineApiSettingsService;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        return ApiResponse.success(engineApiSettingsService.settingsForAdmin());
    }

    @PutMapping
    public ApiResponse<Map<String, String>> update(@Valid @RequestBody UpdateSettingsRequest request) {
        return ApiResponse.success(engineApiSettingsService.updateSettings(request.settings()));
    }
}
