package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.entity.ModelAccountRoutingPool;
import com.aiminilab.aitoolmarket.agent.mapper.ModelAccountRoutingPoolMapper;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelAccountRoutingPoolServiceTest {

    @Mock
    private ModelAccountRoutingPoolMapper poolMapper;
    @Mock
    private VendorCodeResolver vendorCodeResolver;

    private ModelAccountRoutingPoolService service;

    @BeforeEach
    void setUp() {
        service = new ModelAccountRoutingPoolService(poolMapper, vendorCodeResolver);
    }

    @Test
    void upsertCanonicalizesAliyunVendorAlias() {
        ModelAccountRoutingPool pool = new ModelAccountRoutingPool();
        pool.setId(7L);
        pool.setVendorCode("qwen");
        pool.setPoolName("HappyHorse Pool");
        pool.setPoolKey("happyhorse pool");
        when(vendorCodeResolver.requireConcreteVendorCode("dashscope")).thenReturn("qwen");
        when(poolMapper.findByVendorCodeAndPoolKey("qwen", "happyhorse pool")).thenReturn(pool);

        ModelAccountRoutingPool resolved = service.resolveOrCreate("dashscope", " HappyHorse Pool ");

        assertThat(resolved).isSameAs(pool);
        verify(poolMapper).upsert("qwen", "HappyHorse Pool", "happyhorse pool");
    }

    @Test
    void virtualGatewayVendorCannotCreateRoutingPool() {
        doThrow(new BusinessException(
                ErrorCode.PARAM_ERROR,
                "openai_gateway is a compatibility channel, not a vendor; select the actual credential issuer"
        )).when(vendorCodeResolver).requireConcreteVendorCode("openai_gateway");

        assertThatThrownBy(() -> service.resolveOrCreate("openai_gateway", "Legacy Pool"))
                .hasMessageContaining("actual credential issuer");
        verify(poolMapper, never()).upsert("openai_gateway", "Legacy Pool", "legacy pool");
    }
}
