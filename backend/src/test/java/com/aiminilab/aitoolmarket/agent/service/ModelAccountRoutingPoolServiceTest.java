package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.entity.ModelAccountRoutingPool;
import com.aiminilab.aitoolmarket.agent.mapper.ModelAccountRoutingPoolMapper;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(vendorCodeResolver.canonicalVendorCode("dashscope")).thenReturn("qwen");
        when(poolMapper.findByVendorCodeAndPoolKey("qwen", "happyhorse pool")).thenReturn(pool);

        ModelAccountRoutingPool resolved = service.resolveOrCreate("dashscope", " HappyHorse Pool ");

        assertThat(resolved).isSameAs(pool);
        verify(poolMapper).upsert("qwen", "HappyHorse Pool", "happyhorse pool");
    }
}
