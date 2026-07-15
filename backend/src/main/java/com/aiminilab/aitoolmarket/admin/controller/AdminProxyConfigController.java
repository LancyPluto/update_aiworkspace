package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.proxy.ProxyConfigRequest;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyConfigResponse;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyConfigService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyTestResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/proxy-config")
public class AdminProxyConfigController {
    private final ProxyConfigService proxyConfigService;
    private final MihomoRuntimeService mihomoRuntimeService;

    public AdminProxyConfigController(
            ProxyConfigService proxyConfigService,
            MihomoRuntimeService mihomoRuntimeService
    ) {
        this.proxyConfigService = proxyConfigService;
        this.mihomoRuntimeService = mihomoRuntimeService;
    }

    @GetMapping
    public ApiResponse<ProxyConfigResponse> getConfig() {
        return ApiResponse.success(proxyConfigService.getConfig());
    }

    @PutMapping
    public ApiResponse<ProxyConfigResponse> update(@RequestBody ProxyConfigRequest request) {
        return ApiResponse.success(proxyConfigService.update(request, AuthContext.get().userId()));
    }

    @PostMapping("/test")
    public ApiResponse<ProxyTestResponse> testConnection() {
        return ApiResponse.success(proxyConfigService.testConnection());
    }

    @GetMapping("/runtime")
    public ApiResponse<MihomoRuntimeResponse> runtime() {
        return ApiResponse.success(mihomoRuntimeService.status());
    }

    @PostMapping("/apply")
    public ApiResponse<MihomoRuntimeResponse> apply() {
        return ApiResponse.success(mihomoRuntimeService.apply());
    }
}
