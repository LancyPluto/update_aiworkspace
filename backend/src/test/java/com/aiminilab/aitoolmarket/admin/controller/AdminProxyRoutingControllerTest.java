package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingDiagnosticsService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingResponse;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyRoutingUpdateRequest;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminProxyRoutingControllerTest {

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void savingRoutingRulesAppliesMihomoBeforeReportingSuccess() {
        ProxyRoutingService routing = mock(ProxyRoutingService.class);
        ProxyRoutingDiagnosticsService diagnostics = mock(ProxyRoutingDiagnosticsService.class);
        MihomoRuntimeService runtime = mock(MihomoRuntimeService.class);
        ProxyRoutingUpdateRequest request = mock(ProxyRoutingUpdateRequest.class);
        ProxyRoutingResponse response = mock(ProxyRoutingResponse.class);
        when(routing.update(request, 7L)).thenReturn(response);
        when(runtime.apply()).thenReturn(new MihomoRuntimeResponse(true, true, "", null, "applied"));
        AuthContext.set(new AuthUser(7L, "admin", "ADMIN"));
        AdminProxyRoutingController controller = new AdminProxyRoutingController(routing, diagnostics, runtime);

        controller.update(request);

        verify(runtime).apply();
    }
}
