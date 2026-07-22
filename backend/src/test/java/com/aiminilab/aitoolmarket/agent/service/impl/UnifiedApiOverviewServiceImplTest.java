package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiModelItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiOverviewResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountMigrationService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedApiOverviewServiceImplTest {

    @Test
    void balanceWarningOnlyCountsNegativeAmounts() {
        ModelVendorAccount positive = accountWithBalance("18.59", "SUSPECTED_INSUFFICIENT");
        ModelVendorAccount zero = accountWithBalance("0.00", "LOW");
        ModelVendorAccount negative = accountWithBalance("-0.01", "OK");

        assertFalse(UnifiedApiOverviewServiceImpl.isNegativeBalance(positive));
        assertFalse(UnifiedApiOverviewServiceImpl.isNegativeBalance(zero));
        assertTrue(UnifiedApiOverviewServiceImpl.isNegativeBalance(negative));
    }

    @Test
    void configuredVendorExposesSupportedProvidersForModelCapabilityEditing() {
        ModelVendorAccountMigrationService migrationService = mock(ModelVendorAccountMigrationService.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelProviderRegistry providerRegistry = mock(ModelProviderRegistry.class);
        ModelCapabilitiesCodec capabilitiesCodec = mock(ModelCapabilitiesCodec.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AccountModelRouteStateMapper routeStateMapper = mock(AccountModelRouteStateMapper.class);
        UnifiedApiOverviewServiceImpl service = new UnifiedApiOverviewServiceImpl(
                migrationService,
                accountMapper,
                modelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                capabilitiesCodec,
                capabilityService,
                routeStateMapper,
                new ObjectMapper()
        );
        ModelVendorAccount account = routingAccount(10L, "volcengine");
        account.setVendorCode("volcengine");
        AgentModelConfig model = routingModel(1L, 10L, null);
        model.setProvider("volcengine_images");
        model.setModelName("doubao-seedream-4-5-251128");
        when(accountMapper.findAllActive()).thenReturn(List.of(account));
        when(modelConfigMapper.findAllActive()).thenReturn(List.of(model));
        when(accountMapper.countActiveModelsByAccountId(anyLong())).thenReturn(1);
        when(vendorCodeResolver.resolveVendorCode(anyString(), any(), any(), any())).thenReturn("volcengine");
        when(vendorCodeResolver.vendorLabel("volcengine")).thenReturn("Volcengine");
        when(vendorCodeResolver.vendorIconAsset("volcengine")).thenReturn("volcengine");
        when(vendorCodeResolver.vendorCatalog()).thenReturn(Map.of("volcengine", "Volcengine"));
        when(providerRegistry.listAll()).thenReturn(List.of(
                provider("volcengine_images", "Volcengine images", List.of("IMAGE_GENERATION")),
                provider("seedance", "Seedance video", List.of("VIDEO_GENERATION"))
        ));
        when(capabilitiesCodec.parse(any())).thenReturn(List.of("IMAGE_GENERATION"));

        UnifiedApiOverviewResponse response = service.overview();

        assertThat(response.vendors()).singleElement().satisfies(vendor ->
                assertThat(vendor.supportedProviders())
                        .containsExactly("seedance", "volcengine_images"));
    }

    @Test
    void unconfiguredInfiniteTalkVendorExposesItsVideoProvider() {
        ModelVendorAccountMigrationService migrationService = mock(ModelVendorAccountMigrationService.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelProviderRegistry providerRegistry = mock(ModelProviderRegistry.class);
        ModelCapabilitiesCodec capabilitiesCodec = mock(ModelCapabilitiesCodec.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AccountModelRouteStateMapper routeStateMapper = mock(AccountModelRouteStateMapper.class);
        UnifiedApiOverviewServiceImpl service = new UnifiedApiOverviewServiceImpl(
                migrationService,
                accountMapper,
                modelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                capabilitiesCodec,
                capabilityService,
                routeStateMapper,
                new ObjectMapper()
        );
        when(accountMapper.findAllActive()).thenReturn(List.of());
        when(modelConfigMapper.findAllActive()).thenReturn(List.of());
        when(vendorCodeResolver.vendorCatalog()).thenReturn(Map.of("infinitetalk", "InfiniteTalk"));
        when(vendorCodeResolver.resolveVendorCode(anyString(), any(), any(), any()))
                .thenReturn("infinitetalk");
        when(vendorCodeResolver.vendorIconAsset("infinitetalk")).thenReturn("infinitetalk");
        when(providerRegistry.listAll()).thenReturn(List.of(
                provider("infinitetalk", "InfiniteTalk video", List.of("VIDEO_GENERATION"))
        ));

        UnifiedApiOverviewResponse response = service.overview();

        assertThat(response.unconfiguredVendors()).singleElement().satisfies(vendor -> {
            assertThat(vendor.vendorCode()).isEqualTo("infinitetalk");
            assertThat(vendor.supportedProviders()).containsExactly("infinitetalk");
        });
    }

    @Test
    void cachedModelProbeFailureDoesNotExcludeRoutingPreviewCandidate() {
        ModelVendorAccountMigrationService migrationService = mock(ModelVendorAccountMigrationService.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelProviderRegistry providerRegistry = mock(ModelProviderRegistry.class);
        ModelCapabilitiesCodec capabilitiesCodec = mock(ModelCapabilitiesCodec.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AccountModelRouteStateMapper routeStateMapper = mock(AccountModelRouteStateMapper.class);
        UnifiedApiOverviewServiceImpl service = new UnifiedApiOverviewServiceImpl(
                migrationService,
                accountMapper,
                modelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                capabilitiesCodec,
                capabilityService,
                routeStateMapper,
                new ObjectMapper()
        );
        ModelVendorAccount sourceAccount = routingAccount(10L, "source");
        ModelVendorAccount candidateAccount = routingAccount(20L, "candidate");
        AgentModelConfig source = routingModel(1L, 10L, null);
        AgentModelConfig cachedFailure = routingModel(2L, 20L, false);
        when(accountMapper.findAllActive()).thenReturn(List.of(sourceAccount, candidateAccount));
        when(modelConfigMapper.findAllActive()).thenReturn(List.of(source, cachedFailure));
        when(accountMapper.countActiveModelsByAccountId(anyLong())).thenReturn(1);
        when(vendorCodeResolver.resolveVendorCode(anyString(), any(), any(), any())).thenReturn("openai");
        when(vendorCodeResolver.resolveEffectiveVendorCode(any(ModelVendorAccount.class))).thenReturn("openai");
        when(vendorCodeResolver.canonicalVendorCode(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vendorCodeResolver.vendorLabel("openai")).thenReturn("OpenAI");
        when(vendorCodeResolver.vendorIconAsset("openai")).thenReturn(null);
        when(vendorCodeResolver.vendorCatalog()).thenReturn(Map.of("openai", "OpenAI"));
        when(capabilitiesCodec.parse(any())).thenReturn(List.of("IMAGE_GENERATION"));
        when(capabilityService.resolveCapabilities(any())).thenReturn(List.of("IMAGE_GENERATION"));

        UnifiedApiOverviewResponse response = service.overview();

        assertThat(response.vendors()).singleElement().satisfies(vendor ->
                assertThat(vendor.models())
                        .extracting(UnifiedApiModelItemResponse::routingExclusionReason)
                        .containsOnlyNulls());
    }

    @Test
    void accountModeDoesNotReportAPoolExclusion() {
        ModelVendorAccountMigrationService migrationService = mock(ModelVendorAccountMigrationService.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelProviderRegistry providerRegistry = mock(ModelProviderRegistry.class);
        ModelCapabilitiesCodec capabilitiesCodec = mock(ModelCapabilitiesCodec.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AccountModelRouteStateMapper routeStateMapper = mock(AccountModelRouteStateMapper.class);
        UnifiedApiOverviewServiceImpl service = new UnifiedApiOverviewServiceImpl(
                migrationService,
                accountMapper,
                modelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                capabilitiesCodec,
                capabilityService,
                routeStateMapper,
                new ObjectMapper()
        );
        ModelVendorAccount account = routingAccount(10L, "single");
        account.setLoadBalanceEnabled(false);
        account.setRoutingPoolId(null);
        AgentModelConfig model = routingModel(1L, 10L, null);
        model.setRoutingPoolId(null);
        when(accountMapper.findAllActive()).thenReturn(List.of(account));
        when(modelConfigMapper.findAllActive()).thenReturn(List.of(model));
        when(accountMapper.countActiveModelsByAccountId(anyLong())).thenReturn(1);
        when(vendorCodeResolver.resolveVendorCode(anyString(), any(), any(), any())).thenReturn("openai");
        when(vendorCodeResolver.resolveEffectiveVendorCode(any(ModelVendorAccount.class))).thenReturn("openai");
        when(vendorCodeResolver.canonicalVendorCode(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vendorCodeResolver.vendorLabel("openai")).thenReturn("OpenAI");
        when(vendorCodeResolver.vendorIconAsset("openai")).thenReturn(null);
        when(vendorCodeResolver.vendorCatalog()).thenReturn(Map.of("openai", "OpenAI"));
        when(capabilitiesCodec.parse(any())).thenReturn(List.of("IMAGE_GENERATION"));

        UnifiedApiOverviewResponse response = service.overview();

        assertThat(response.vendors()).singleElement().satisfies(vendor ->
                assertThat(vendor.models()).singleElement().satisfies(item ->
                        assertThat(item.routingExclusionReason()).isNull()));
    }

    @Test
    void poolPreviewReportsWhenEveryCompatibleModelCircuitIsOpen() {
        ModelVendorAccountMigrationService migrationService = mock(ModelVendorAccountMigrationService.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelProviderRegistry providerRegistry = mock(ModelProviderRegistry.class);
        ModelCapabilitiesCodec capabilitiesCodec = mock(ModelCapabilitiesCodec.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AccountModelRouteStateMapper routeStateMapper = mock(AccountModelRouteStateMapper.class);
        UnifiedApiOverviewServiceImpl service = new UnifiedApiOverviewServiceImpl(
                migrationService,
                accountMapper,
                modelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                capabilitiesCodec,
                capabilityService,
                routeStateMapper,
                new ObjectMapper()
        );
        ModelVendorAccount sourceAccount = routingAccount(10L, "source");
        ModelVendorAccount candidateAccount = routingAccount(20L, "candidate");
        AgentModelConfig source = routingModel(1L, 10L, null);
        AgentModelConfig candidate = routingModel(2L, 20L, null);
        when(accountMapper.findAllActive()).thenReturn(List.of(sourceAccount, candidateAccount));
        when(modelConfigMapper.findAllActive()).thenReturn(List.of(source, candidate));
        when(routeStateMapper.selectList(any())).thenReturn(List.of(
                openState(1L), openState(2L)));
        when(accountMapper.countActiveModelsByAccountId(anyLong())).thenReturn(1);
        when(vendorCodeResolver.resolveVendorCode(anyString(), any(), any(), any())).thenReturn("openai");
        when(vendorCodeResolver.resolveEffectiveVendorCode(any(ModelVendorAccount.class))).thenReturn("openai");
        when(vendorCodeResolver.canonicalVendorCode(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vendorCodeResolver.vendorLabel("openai")).thenReturn("OpenAI");
        when(vendorCodeResolver.vendorIconAsset("openai")).thenReturn(null);
        when(vendorCodeResolver.vendorCatalog()).thenReturn(Map.of("openai", "OpenAI"));
        when(capabilitiesCodec.parse(any())).thenReturn(List.of("IMAGE_GENERATION"));
        when(capabilityService.resolveCapabilities(any())).thenReturn(List.of("IMAGE_GENERATION"));

        UnifiedApiOverviewResponse response = service.overview();

        assertThat(response.vendors()).singleElement().satisfies(vendor ->
                assertThat(vendor.models()).allSatisfy(model -> {
                    assertThat(model.routingPoolId()).isEqualTo(100L);
                    assertThat(model.routingPoolName()).isEqualTo("main-pool");
                    assertThat(model.routingExclusionReason()).isEqualTo("CIRCUIT_OPEN");
                }));
    }

    private static ModelVendorAccount accountWithBalance(String amount, String status) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setBalanceAmount(new BigDecimal(amount));
        account.setBalanceStatus(status);
        return account;
    }

    private static ModelVendorAccount routingAccount(Long id, String name) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(id);
        account.setVendorCode("openai");
        account.setAccountName(name);
        account.setEnabled(true);
        account.setLoadBalanceEnabled(true);
        account.setLoadBalanceWeight(100);
        account.setRoutingPoolId(100L);
        account.setRoutingPoolName("main-pool");
        return account;
    }

    private static AgentModelConfig routingModel(Long id, Long accountId, Boolean lastTestSuccess) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(id);
        model.setVendorAccountId(accountId);
        model.setDisplayName("model-" + id);
        model.setConfigCode("model-" + id);
        model.setProvider("openai_images_gateway");
        model.setModelName("gpt-image-2");
        model.setExecutionTask("IMAGE_GENERATION");
        model.setBillingUnit("IMAGE");
        model.setUnitPrice(BigDecimal.ONE);
        model.setCapabilities("[\"IMAGE_GENERATION\"]");
        model.setEnabled(true);
        model.setLastTestSuccess(lastTestSuccess);
        model.setRoutingPoolId(100L);
        return model;
    }

    private static AccountModelRouteState openState(Long modelConfigId) {
        AccountModelRouteState state = new AccountModelRouteState();
        state.setId(100L + modelConfigId);
        state.setModelConfigId(modelConfigId);
        state.setCircuitStatus("OPEN");
        state.setCooldownUntil(LocalDateTime.now().plusMinutes(5));
        state.setInFlightCount(0);
        return state;
    }

    private static ModelProviderDefinition provider(String code, String label, List<String> capabilities) {
        return new ModelProviderDefinition(
                code,
                label,
                capabilities,
                "https://ark.cn-beijing.volces.com",
                "model",
                "PER_CALL",
                null,
                null,
                null,
                "accept_only",
                true,
                false,
                null,
                null,
                null,
                null,
                "test"
        );
    }
}
