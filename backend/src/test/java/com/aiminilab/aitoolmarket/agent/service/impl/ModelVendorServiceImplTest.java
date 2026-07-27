package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.UpsertModelVendorRequest;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendor;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorMapper;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelVendorServiceImplTest {

    @Test
    void adminListHidesDeprecatedVirtualGatewayVendor() {
        ModelVendorMapper vendorMapper = mock(ModelVendorMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        ModelVendor legacy = vendor("openai_gateway", "OpenAI compatible gateway");
        ModelVendor ofox = vendor("ofox", "oFox");
        when(vendorMapper.selectList(null)).thenReturn(List.of(legacy, ofox));
        when(vendorCodeResolver.isDeprecatedVirtualVendorCode("openai_gateway")).thenReturn(true);
        ModelVendorServiceImpl service = service(vendorMapper, vendorCodeResolver);

        assertThat(service.adminListAll())
                .extracting(response -> response.vendorCode())
                .containsExactly("ofox");
    }

    @Test
    void upsertRejectsVirtualGatewayVendorBeforeDatabaseWrite() {
        ModelVendorMapper vendorMapper = mock(ModelVendorMapper.class);
        VendorCodeResolver vendorCodeResolver = mock(VendorCodeResolver.class);
        doThrow(new BusinessException(
                ErrorCode.PARAM_ERROR,
                "openai_gateway is a compatibility channel, not a vendor; select the actual credential issuer"
        )).when(vendorCodeResolver).requireConcreteVendorCode("openai_gateway");
        ModelVendorServiceImpl service = service(vendorMapper, vendorCodeResolver);

        assertThatThrownBy(() -> service.adminUpsert(new UpsertModelVendorRequest(
                "openai_gateway",
                "OpenAI compatible gateway",
                "openrouter",
                70,
                true
        ))).hasMessageContaining("actual credential issuer");
        verifyNoInteractions(vendorMapper);
    }

    private static ModelVendorServiceImpl service(ModelVendorMapper vendorMapper,
                                                  VendorCodeResolver vendorCodeResolver) {
        return new ModelVendorServiceImpl(
                vendorMapper,
                mock(ModelVendorAccountMapper.class),
                mock(AgentModelConfigMapper.class),
                mock(ToolMapper.class),
                mock(BypassCacheService.class),
                new ObjectMapper(),
                vendorCodeResolver
        );
    }

    private static ModelVendor vendor(String code, String label) {
        ModelVendor vendor = new ModelVendor();
        vendor.setVendorCode(code);
        vendor.setVendorLabel(label);
        vendor.setIconAsset(code);
        vendor.setSortOrder(0);
        vendor.setEnabled(true);
        return vendor;
    }
}
