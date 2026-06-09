package com.aiminilab.aitoolmarket.agent.support;

import org.junit.jupiter.api.Test;

import java.net.ProxySelector;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundHttpClientFactoryTest {

    @Test
    void resolveProxySelector_returnsNullWhenEnvMissing() {
        assertThat(OutboundHttpClientFactory.resolveProxySelector()).isNull();
    }

    @Test
    void create_buildsClientWithoutProxyWhenEnvMissing() {
        assertThat(OutboundHttpClientFactory.create(java.time.Duration.ofSeconds(3))).isNotNull();
    }
}
