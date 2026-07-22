package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionModelConfigResponseTest {

    @Test
    void liveModelConfigExposesBillingUnitWithoutPrice() throws Exception {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(8L);
        config.setBillingUnit("PER_CHARACTER");
        config.setUnitPrice(new java.math.BigDecimal("0.02"));

        ExecutionModelConfigResponse response = ExecutionModelConfigResponse.from(config, List.of("TEXT_TO_SPEECH"));
        JsonNode json = new ObjectMapper().valueToTree(response);

        assertThat(response.billingUnit()).isEqualTo("PER_CHARACTER");
        assertThat(json.path("billingUnit").asText()).isEqualTo("PER_CHARACTER");
        assertThat(json.has("unitPrice")).isFalse();
    }

    @Test
    void retryKeepsSnapshotForAuditButOverridesRuntimeProxyPolicy() throws Exception {
        ModelExecutionSnapshot snapshot = new ObjectMapper().readValue("""
                {
                  "id": 9,
                  "baseUrl": "https://api.ofox.ai/v1",
                  "billingUnit": "PER_CHARACTER",
                  "capabilities": [],
                  "proxyPolicy": {
                    "mode": "INHERIT",
                    "proxyUrl": "socks5://historical-upstream.example:1080",
                    "enabled": true,
                    "noProxyHosts": []
                  }
                }
                """, ModelExecutionSnapshot.class);
        ProxyPolicy currentPolicy = new ProxyPolicy(
                "PROXY", "http://mihomo:7890", true, List.of("backend"), List.of(),
                "DIRECT", "http://mihomo:7890", true
        );

        ExecutionModelConfigResponse response = ExecutionModelConfigResponse.from(snapshot, currentPolicy);

        assertThat(response.billingUnit()).isEqualTo("PER_CHARACTER");
        assertThat(response.proxyPolicy()).isSameAs(currentPolicy);
        assertThat(response.proxyPolicy().proxyUrl()).isEqualTo("http://mihomo:7890");
        assertThat(snapshot.proxyPolicy().proxyUrl()).isEqualTo("socks5://historical-upstream.example:1080");
    }
}
