package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.balance.VendorBalanceRefreshService;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeContext;
import com.aiminilab.aitoolmarket.agent.connectivity.ConnectivityProbeRegistry;
import com.aiminilab.aitoolmarket.agent.connectivity.ConnectivityProbeResult;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountTestResponse;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelVendorAccountServiceImplTest {

    @Mock
    private ModelVendorAccountMapper vendorAccountMapper;
    @Mock
    private AgentModelConfigMapper agentModelConfigMapper;
    @Mock
    private VendorCodeResolver vendorCodeResolver;
    @Mock
    private AgentServiceClient agentServiceClient;
    @Mock
    private VendorBalanceRefreshService balanceRefreshService;
    @Mock
    private ConnectivityProbeRegistry connectivityProbeRegistry;
    @Mock
    private AccountModelRouteStateMapper routeStateMapper;

    private ModelVendorAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new ModelVendorAccountServiceImpl(
                vendorAccountMapper,
                agentModelConfigMapper,
                vendorCodeResolver,
                new ModelProviderRegistry(),
                agentServiceClient,
                balanceRefreshService,
                new ModelCapabilitiesCodec(objectMapper),
                objectMapper,
                connectivityProbeRegistry,
                routeStateMapper
        );
    }

    @Test
    void agnesAccountProbeNeverFallsBackToAgentModelRequestOrMutatesModels() {
        ModelVendorAccount account = agnesAccount();
        when(vendorAccountMapper.findActiveById(25L)).thenReturn(account);
        when(vendorCodeResolver.vendorLabel("agnes")).thenReturn("Agnes AI");
        when(connectivityProbeRegistry.probeAccount(any())).thenReturn(Optional.of(
                ConnectivityProbeResult.ok("ACCOUNT_MODELS", 200, 18L, "账户网关与凭据有效")
        ));

        ModelVendorAccountTestResponse response = service.adminTest(25L);

        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("agnes");
        assertThat(response.modelName()).isEmpty();
        assertThat(account.getHealthStatus()).isEqualTo("OK");
        ArgumentCaptor<AccountProbeContext> context = ArgumentCaptor.forClass(AccountProbeContext.class);
        verify(connectivityProbeRegistry).probeAccount(context.capture());
        assertThat(context.getValue().vendorCode()).isEqualTo("agnes");
        assertThat(context.getValue().baseUrl()).isEqualTo("https://apihub.agnes-ai.com/v1");
        assertThat(context.getValue().apiKey()).isEqualTo("agnes-secret");
        verify(agentServiceClient, never()).testModelConfig(any());
        verifyNoInteractions(agentModelConfigMapper);
        verify(vendorAccountMapper).updateAccount(account);
        verify(routeStateMapper).recoverByVendorAccountId(25L);
    }

    @Test
    void billingWarningKeepsAgnesCredentialActive() {
        ModelVendorAccount account = agnesAccount();
        when(vendorAccountMapper.findActiveById(25L)).thenReturn(account);
        when(vendorCodeResolver.vendorLabel("agnes")).thenReturn("Agnes AI");
        when(connectivityProbeRegistry.probeAccount(any())).thenReturn(Optional.of(
                ConnectivityProbeResult.warning(
                        "ACCOUNT_MODELS",
                        429,
                        22L,
                        "凭据有效，但上游返回余额、额度或限流告警（HTTP 429）"
                )
        ));

        ModelVendorAccountTestResponse response = service.adminTest(25L);

        assertThat(response.success()).isTrue();
        assertThat(account.getHealthStatus()).isEqualTo("WARNING");
        assertThat(account.getHealthMessage()).isEqualTo(response.message());
        assertThat(account.getBalanceErrorMessage()).isNull();
        assertThat(account.getApiKey()).isEqualTo("agnes-secret");
        assertThat(account.getEnabled()).isTrue();
        verify(vendorAccountMapper).updateAccount(account);
        verify(routeStateMapper, never()).recoverByVendorAccountId(any());
        verify(agentServiceClient, never()).testModelConfig(any());
    }

    private ModelVendorAccount agnesAccount() {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(25L);
        account.setVendorCode("agnes");
        account.setAccountName("Agnes account");
        account.setBaseUrl("https://apihub.agnes-ai.com/v1");
        account.setApiKey("agnes-secret");
        account.setEnabled(true);
        return account;
    }
}
