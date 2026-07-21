package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void cachedModelProbeFailureDoesNotExcludeRoutingPreviewCandidate() {
        ModelVendorAccountMigrationService migrationService = mock(ModelVendorAccountMigrationService.class);
        ModelVendorAccountMapper accountMapper = mock(ModelVendorAccountMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelProviderRegistry providerRegistry = mock(ModelProviderRegistry.class);
        ModelCapabilitiesCodec capabilitiesCodec = mock(ModelCapabilitiesCodec.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        UnifiedApiOverviewServiceImpl service = new UnifiedApiOverviewServiceImpl(
                migrationService,
                accountMapper,
                modelConfigMapper,
                vendorCodeResolver,
                providerRegistry,
                capabilitiesCodec,
                capabilityService,
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
        return model;
    }
}
