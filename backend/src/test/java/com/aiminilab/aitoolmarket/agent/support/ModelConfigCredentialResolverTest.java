package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelConfigCredentialResolverTest {

    @Test
    void modelProxyFieldsOverrideVendorAccountExtraAuth() throws Exception {
        ModelVendorAccountMapper mapper = mock(ModelVendorAccountMapper.class);
        ModelProviderRegistry registry = mock(ModelProviderRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(42L);
        account.setEnabled(true);
        account.setApiKey("account-key");
        account.setExtraAuthJson("{\"accessKey\":\"account-ak\",\"proxyMode\":\"enabled\",\"proxyUrl\":\"http://account:7890\"}");
        when(mapper.findActiveById(42L)).thenReturn(account);

        AgentModelConfig config = new AgentModelConfig();
        config.setVendorAccountId(42L);
        config.setProvider("suno_music");
        config.setExtraAuthJson("{\"style\":\"pop\",\"proxyMode\":\"disabled\",\"proxyUrl\":\"http://model:7890\"}");

        AgentModelConfig resolved = new ModelConfigCredentialResolver(mapper, registry, objectMapper).resolveForExecution(config);

        JsonNode extra = objectMapper.readTree(resolved.getExtraAuthJson());
        assertThat(resolved.getApiKey()).isEqualTo("account-key");
        assertThat(extra.get("accessKey").asText()).isEqualTo("account-ak");
        assertThat(extra.get("style").asText()).isEqualTo("pop");
        assertThat(extra.get("proxyMode").asText()).isEqualTo("disabled");
        assertThat(extra.get("proxyUrl").asText()).isEqualTo("http://model:7890");
    }

    @Test
    void blankModelProxyUrlDoesNotEraseVendorAccountProxyUrl() throws Exception {
        ModelVendorAccountMapper mapper = mock(ModelVendorAccountMapper.class);
        ModelProviderRegistry registry = mock(ModelProviderRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(43L);
        account.setEnabled(true);
        account.setApiKey("account-key");
        account.setExtraAuthJson("{\"proxyMode\":\"enabled\",\"proxyUrl\":\"http://account:7890\"}");
        when(mapper.findActiveById(43L)).thenReturn(account);

        AgentModelConfig config = new AgentModelConfig();
        config.setVendorAccountId(43L);
        config.setProvider("suno_music");
        config.setExtraAuthJson("{\"proxyMode\":\"inherit\",\"proxyUrl\":\"\"}");

        AgentModelConfig resolved = new ModelConfigCredentialResolver(mapper, registry, objectMapper).resolveForExecution(config);

        JsonNode extra = objectMapper.readTree(resolved.getExtraAuthJson());
        assertThat(extra.get("proxyMode").asText()).isEqualTo("inherit");
        assertThat(extra.get("proxyUrl").asText()).isEqualTo("http://account:7890");
    }
}
