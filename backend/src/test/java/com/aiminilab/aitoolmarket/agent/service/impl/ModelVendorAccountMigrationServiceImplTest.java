package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelVendorAccountMigrationServiceImplTest {

    @Test
    void unboundGenericGatewayWithUnknownHostFailsClosed() {
        AgentModelConfigMapper configMapper = mock(AgentModelConfigMapper.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelVendorAccountMigrationServiceImpl service = new ModelVendorAccountMigrationServiceImpl(
                configMapper,
                accountMapper,
                vendorCodeResolver
        );
        AgentModelConfig gateway = new AgentModelConfig();
        gateway.setId(81L);
        gateway.setProvider("openai_images_gateway");
        gateway.setBaseUrl("https://unknown-gateway.example/v1");
        when(configMapper.findAllActive()).thenReturn(List.of(gateway));
        when(vendorCodeResolver.resolveVendorCode(gateway)).thenReturn("other");
        when(vendorCodeResolver.usesBoundAccountVendor("openai_images_gateway")).thenReturn(true);

        assertThatThrownBy(service::migrateIfNeeded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actual credential issuer");
        verify(accountMapper, never()).insertAccount(any());
        verify(configMapper, never()).updateVendorAccountId(any(), any(), any());
    }

    @Test
    void preservesExplicitAccountsWithCompatibleCredentials() {
        AgentModelConfigMapper configMapper = mock(AgentModelConfigMapper.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelVendorAccountMigrationServiceImpl service = new ModelVendorAccountMigrationServiceImpl(
                configMapper,
                accountMapper,
                vendorCodeResolver
        );

        ModelVendorAccount primary = account(11L, "百炼账户 1");
        ModelVendorAccount replacement = account(22L, "百炼账户 2");
        AgentModelConfig happyHorse = new AgentModelConfig();
        happyHorse.setId(59L);
        happyHorse.setVendorAccountId(replacement.getId());

        when(configMapper.findAllActive()).thenReturn(List.of(happyHorse));
        when(accountMapper.findAllActive()).thenReturn(List.of(primary, replacement));
        when(accountMapper.countActiveModelsByAccountId(primary.getId())).thenReturn(3);
        when(accountMapper.countActiveModelsByAccountId(replacement.getId())).thenReturn(1);

        service.migrateIfNeeded();

        verify(accountMapper, never()).softDelete(any());
        verify(configMapper, never()).updateVendorAccountId(any(), any(), any());
    }

    private static ModelVendorAccount account(Long id, String name) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(id);
        account.setVendorCode("qwen");
        account.setAccountName(name);
        account.setBaseUrl("https://dashscope.aliyuncs.com");
        account.setApiKey("same-key");
        account.setBalanceQueryMode("MANUAL");
        account.setEnabled(true);
        return account;
    }
}
