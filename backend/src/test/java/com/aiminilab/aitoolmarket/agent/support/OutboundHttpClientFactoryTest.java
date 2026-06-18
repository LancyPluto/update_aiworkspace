package com.aiminilab.aitoolmarket.agent.support;

import org.junit.jupiter.api.Test;

import java.net.ProxySelector;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundHttpClientFactoryTest {

    @Test
    void resolveProxySelector_consistentWithEnv() {
        boolean hasProxy = System.getenv("HTTPS_PROXY") != null || System.getenv("HTTP_PROXY") != null;
        if (hasProxy) {
            assertThat(OutboundHttpClientFactory.resolveProxySelector()).isNotNull();
        } else {
            assertThat(OutboundHttpClientFactory.resolveProxySelector()).isNull();
        }
    }

    @Test
    void create_buildsClientWithoutProxyWhenEnvMissing() {
        assertThat(OutboundHttpClientFactory.create(java.time.Duration.ofSeconds(3))).isNotNull();
    }
}
