package com.aiminilab.aitoolmarket.ppt.security;

import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PptExecutionTokenServiceTest {
    private PptExecutionTokenService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.setInternalApiToken("test-internal-token-that-is-not-a-provider-key");
        service = new PptExecutionTokenService(properties, new ObjectMapper());
    }

    @Test
    void issuedTokenIsBoundToProjectJobAndCapabilities() {
        var issued = service.issue(7L, 9L, List.of("TEXT", "IMAGE"), 300);

        var claims = service.require("Bearer " + issued.token());

        assertThat(claims.projectId()).isEqualTo(7L);
        assertThat(claims.jobId()).isEqualTo(9L);
        assertThat(claims.capabilities()).containsExactly("TEXT", "IMAGE");
        claims.requireScope(7L, 9L, "image");
        assertThatThrownBy(() -> claims.requireScope(7L, 10L, "IMAGE"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        var issued = service.issue(7L, 9L, List.of("TEXT"), 300);

        assertThatThrownBy(() -> service.require("Bearer " + issued.token() + "x"))
                .isInstanceOf(BusinessException.class);
    }
}
