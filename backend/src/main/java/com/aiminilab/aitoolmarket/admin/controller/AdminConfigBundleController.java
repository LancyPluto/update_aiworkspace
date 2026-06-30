package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleDto;
import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleImportResult;
import com.aiminilab.aitoolmarket.admin.service.ConfigBundleService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/config-bundles")
public class AdminConfigBundleController {

    private final ConfigBundleService configBundleService;

    public AdminConfigBundleController(ConfigBundleService configBundleService) {
        this.configBundleService = configBundleService;
    }

    @GetMapping("/export")
    public ApiResponse<ConfigBundleDto> exportBundle(
            @RequestParam(name = "includeSecrets", defaultValue = "false") boolean includeSecrets,
            @RequestParam(name = "toolCodes", required = false) List<String> toolCodes,
            @RequestParam(name = "includeMediaAssets", defaultValue = "true") boolean includeMediaAssets) {
        return ApiResponse.success(configBundleService.exportBundle(
                AuthContext.get().userId(), includeSecrets, toolCodes, includeMediaAssets));
    }

    @PostMapping("/import")
    public ApiResponse<ConfigBundleImportResult> importBundle(@RequestBody ConfigBundleDto bundle) {
        return ApiResponse.success(configBundleService.importBundle(bundle, AuthContext.get().userId()));
    }
}
