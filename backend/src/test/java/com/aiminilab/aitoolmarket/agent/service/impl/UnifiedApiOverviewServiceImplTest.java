package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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

    private static ModelVendorAccount accountWithBalance(String amount, String status) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setBalanceAmount(new BigDecimal(amount));
        account.setBalanceStatus(status);
        return account;
    }
}
