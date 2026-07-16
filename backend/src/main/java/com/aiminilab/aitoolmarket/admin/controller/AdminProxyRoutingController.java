package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyDomainTestRequest;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyDomainTestResponse;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingDiagnosticsService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingResponse;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingUpdateRequest;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/proxy-config/routing")
public class AdminProxyRoutingController {
    private final ProxyRoutingService routingService;
    private final ProxyRoutingDiagnosticsService diagnosticsService;
    private final MihomoRuntimeService runtimeService;

    public AdminProxyRoutingController(
            ProxyRoutingService routingService,
            ProxyRoutingDiagnosticsService diagnosticsService,
            MihomoRuntimeService runtimeService
    ) {
        this.routingService = routingService;
        this.diagnosticsService = diagnosticsService;
        this.runtimeService = runtimeService;
    }

    @GetMapping
    public ApiResponse<ProxyRoutingResponse> get() {
        return ApiResponse.success(routingService.getConfig());
    }

    @PutMapping
    public ApiResponse<ProxyRoutingResponse> update(@RequestBody ProxyRoutingUpdateRequest request) {
        ProxyRoutingResponse response = routingService.update(request, AuthContext.get().userId());
        MihomoRuntimeResponse runtime = runtimeService.apply();
        if (runtime.managed() && !runtime.available()) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "公网分流规则已保存，但应用到 Mihomo 失败：" + runtime.message()
            );
        }
        return ApiResponse.success(response);
    }

    @PostMapping("/test")
    public ApiResponse<ProxyDomainTestResponse> test(@RequestBody ProxyDomainTestRequest request) {
        return ApiResponse.success(diagnosticsService.test(request, AuthContext.get().userId()));
    }
}
