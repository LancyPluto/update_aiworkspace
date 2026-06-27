package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VendorBalanceRefreshServiceTest {

    @Test
    void refreshClearsStaleInsufficientStatusWhenBalanceBecomesPositive() {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(1L);
        account.setVendorCode("deepseek");
        account.setBalanceQueryMode("REST_API");
        account.setBalanceStatus("SUSPECTED_INSUFFICIENT");
        account.setBalanceAmount(new BigDecimal("-1.00"));
        account.setBalanceErrorMessage("old error");

        VendorBalanceAdapterRegistry registry = mock(VendorBalanceAdapterRegistry.class);
        when(registry.hasRestAdapter("deepseek")).thenReturn(true);
        when(registry.query(account)).thenReturn(BalanceQueryResult.ok(new BigDecimal("18.59"), "CNY"));

        VendorBalanceRefreshService service = new VendorBalanceRefreshService(
                mock(ModelVendorAccountMapper.class),
                registry
        );
        service.refresh(account);

        assertEquals(new BigDecimal("18.59"), account.getBalanceAmount());
        assertEquals("OK", account.getBalanceStatus());
        assertNull(account.getBalanceErrorMessage());
    }

    @Test
    void refreshFailureKeepsExistingBalanceAmount() {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(2L);
        account.setVendorCode("openai");
        account.setBalanceQueryMode("REST_API");
        account.setBalanceStatus("OK");
        account.setBalanceAmount(new BigDecimal("42.00"));

        VendorBalanceAdapterRegistry registry = mock(VendorBalanceAdapterRegistry.class);
        when(registry.hasRestAdapter("openai")).thenReturn(true);
        when(registry.query(account)).thenReturn(BalanceQueryResult.unsupported("中转站未提供标准余额 JSON 接口"));

        VendorBalanceRefreshService service = new VendorBalanceRefreshService(
                mock(ModelVendorAccountMapper.class),
                registry
        );
        service.refresh(account);

        assertEquals(new BigDecimal("42.00"), account.getBalanceAmount());
        assertEquals("UNKNOWN", account.getBalanceStatus());
        assertEquals("中转站未提供标准余额 JSON 接口", account.getBalanceErrorMessage());
    }
}
