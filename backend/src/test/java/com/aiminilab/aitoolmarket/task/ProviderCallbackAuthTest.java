package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.metrics.AuthMetrics;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.config.AuthInterceptor;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderCallbackAuthTest {

    @Test
    void exactSunoCallbackPathIsPublicButBroaderProviderPathIsNot() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(
                mock(JwtTokenProvider.class),
                new ObjectMapper(),
                mock(ErrorContractResponseFactory.class),
                mock(InternalRequestSignatureVerifier.class),
                mock(UserMapper.class),
                mock(AuthMetrics.class)
        );
        String token = "a".repeat(64);
        MockHttpServletRequest allowed = new MockHttpServletRequest(
                "POST",
                "/api/v1/provider-callbacks/suno/music/" + token
        );
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(allowed, allowedResponse, new Object())).isTrue();

        MockHttpServletRequest denied = new MockHttpServletRequest(
                "POST",
                "/api/v1/provider-callbacks/other/music/" + token
        );
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(denied, deniedResponse, new Object())).isFalse();
        assertThat(deniedResponse.getStatus()).isEqualTo(401);
    }
}
