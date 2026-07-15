package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoRuntimeService;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyConfigRequest;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyConfigResponse;
import com.aiminilab.aitoolmarket.admin.proxy.ProxyConfigService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminProxyConfigControllerTest {

    private final ProxyConfigService proxyConfigService = mock(ProxyConfigService.class);
    private final MihomoRuntimeService runtimeService = mock(MihomoRuntimeService.class);
    private final AdminProxyConfigController controller = new AdminProxyConfigController(proxyConfigService, runtimeService);
    private final ProxyConfigRequest request = mock(ProxyConfigRequest.class);

    @BeforeEach
    void setUp() {
        AuthContext.set(new AuthUser(7L, "admin", "ADMIN"));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void updateAutomaticallyAppliesSavedConfig() {
        ProxyConfigResponse saved = mock(ProxyConfigResponse.class);
        when(proxyConfigService.update(request, 7L)).thenReturn(saved);
        when(runtimeService.apply()).thenReturn(new MihomoRuntimeResponse(true, true, "", null, "applied"));

        controller.update(request);

        verify(runtimeService).apply();
    }

    @Test
    void updateReportsManagedRuntimeApplyFailure() {
        ProxyConfigResponse saved = mock(ProxyConfigResponse.class);
        when(proxyConfigService.update(request, 7L)).thenReturn(saved);
        when(runtimeService.apply()).thenReturn(new MihomoRuntimeResponse(
                true,
                false,
                "",
                null,
                "Mihomo reload returned HTTP 500"
        ));

        assertThatThrownBy(() -> controller.update(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reload returned HTTP 500");

        verify(runtimeService).apply();
    }
}
