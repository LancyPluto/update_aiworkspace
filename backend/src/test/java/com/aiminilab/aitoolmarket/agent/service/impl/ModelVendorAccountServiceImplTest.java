package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.balance.VendorBalanceRefreshService;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeContext;
import com.aiminilab.aitoolmarket.agent.connectivity.ConnectivityProbeRegistry;
import com.aiminilab.aitoolmarket.agent.connectivity.ConnectivityProbeResult;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountTestResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelProviderMetadataService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        ModelProviderRegistry providerRegistry = new ModelProviderRegistry();
        ReflectionTestUtils.invokeMethod(providerRegistry, "load");
        service = new ModelVendorAccountServiceImpl(
                vendorAccountMapper,
                agentModelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                new ModelProviderMetadataService(null, providerRegistry, objectMapper),
                agentServiceClient,
                balanceRefreshService,
                new ModelCapabilitiesCodec(objectMapper),
                new ModelConfigCredentialResolver(vendorAccountMapper, providerRegistry, objectMapper),
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

    @Test
    void accountWithoutEnabledModelReturnsClearFailureWithoutExternalCall() {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(31L);
        account.setVendorCode("suno");
        account.setAccountName("Suno account");
        account.setBaseUrl("https://api.sunoapi.org");
        account.setApiKey("suno-secret");
        account.setEnabled(true);
        when(vendorAccountMapper.findActiveById(31L)).thenReturn(account);
        when(vendorCodeResolver.vendorLabel("suno")).thenReturn("Suno");
        when(connectivityProbeRegistry.probeAccount(any())).thenReturn(Optional.empty());
        when(agentModelConfigMapper.findFirstEnabledByVendorAccountId(31L)).thenReturn(null);

        ModelVendorAccountTestResponse response = service.adminTest(31L);

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("绑定并启用模型");
        assertThat(response.provider()).isEmpty();
        assertThat(response.modelName()).isEmpty();
        assertThat(account.getHealthStatus()).isEqualTo("ERROR");
        verify(agentServiceClient, never()).testModelConfig(any());
        verify(vendorAccountMapper).updateAccount(account);
        verify(routeStateMapper, never()).recoverByVendorAccountId(any());
    }

    @Test
    void linkedModelRequestUsesExtraAuthApiKeyAndMergesAccountAndModelFields() throws Exception {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(32L);
        account.setVendorCode("moonshot");
        account.setAccountName("Moonshot account");
        account.setBaseUrl("https://api.moonshot.cn/v1");
        account.setExtraAuthJson("{\"apiKey\":\"account-secret\",\"region\":\"cn\",\"shared\":\"account\",\"proxyMode\":\"enabled\",\"proxyUrl\":\"http://account:7890\"}");
        account.setEnabled(true);

        AgentModelConfig linked = new AgentModelConfig();
        linked.setId(320L);
        linked.setVendorAccountId(32L);
        linked.setProvider("openai_compatible");
        linked.setModelName("kimi-k2.6");
        linked.setBaseUrl("https://model.moonshot.cn/v1");
        linked.setExtraAuthJson("{\"requestMode\":\"thinking\",\"shared\":\"model\",\"proxyMode\":\"disabled\",\"proxyUrl\":\"http://model:7890\"}");
        linked.setEnabled(true);

        when(vendorAccountMapper.findActiveById(32L)).thenReturn(account);
        when(vendorCodeResolver.vendorLabel("moonshot")).thenReturn("Moonshot");
        when(connectivityProbeRegistry.probeAccount(any())).thenReturn(Optional.empty());
        when(agentModelConfigMapper.findFirstEnabledByVendorAccountId(32L)).thenReturn(linked);
        when(agentServiceClient.testModelConfig(any())).thenReturn(new AgentModelConfigTestResponse(
                true,
                "openai_compatible",
                "kimi-k2.6",
                12L,
                "connected",
                ""
        ));

        ModelVendorAccountTestResponse response = service.adminTest(32L);

        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("openai_compatible");
        assertThat(response.modelName()).isEqualTo("kimi-k2.6");
        ArgumentCaptor<AgentModelConfigRequest> request = ArgumentCaptor.forClass(AgentModelConfigRequest.class);
        verify(agentServiceClient).testModelConfig(request.capture());
        assertThat(request.getValue().apiKey()).isEqualTo("account-secret");
        assertThat(request.getValue().provider()).isEqualTo("openai_compatible");
        assertThat(request.getValue().modelName()).isEqualTo("kimi-k2.6");
        assertThat(request.getValue().baseUrl()).isEqualTo("https://model.moonshot.cn/v1");
        JsonNode extraAuth = new ObjectMapper().readTree(request.getValue().extraAuthJson());
        assertThat(extraAuth.path("apiKey").asText()).isEqualTo("account-secret");
        assertThat(extraAuth.path("region").asText()).isEqualTo("cn");
        assertThat(extraAuth.path("shared").asText()).isEqualTo("account");
        assertThat(extraAuth.path("requestMode").asText()).isEqualTo("thinking");
        assertThat(extraAuth.path("proxyMode").asText()).isEqualTo("disabled");
        assertThat(extraAuth.path("proxyUrl").asText()).isEqualTo("http://model:7890");
    }

    @Test
    void createCanonicalizesAliyunVendorAliasBeforeInsert() {
        when(vendorCodeResolver.canonicalVendorCode("bailian_happyhorse")).thenReturn("qwen");
        when(vendorCodeResolver.vendorLabel("qwen")).thenReturn("阿里云百炼");
        ModelVendorAccountRequest request = new ModelVendorAccountRequest(
                "bailian_happyhorse",
                "HappyHorse account",
                "https://dashscope.aliyuncs.com",
                "test-key",
                false,
                null,
                false,
                null,
                null,
                null,
                false,
                "MANUAL",
                null,
                "CNY",
                null,
                true,
                null,
                null
        );

        service.adminCreate(request);

        ArgumentCaptor<ModelVendorAccount> account = ArgumentCaptor.forClass(ModelVendorAccount.class);
        verify(vendorAccountMapper).insertAccount(account.capture());
        assertThat(account.getValue().getVendorCode()).isEqualTo("qwen");
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
