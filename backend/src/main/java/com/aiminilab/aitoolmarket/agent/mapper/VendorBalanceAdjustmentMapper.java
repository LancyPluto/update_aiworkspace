package com.aiminilab.aitoolmarket.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

public interface VendorBalanceAdjustmentMapper {

    @Insert("""
            INSERT INTO vendor_balance_adjustments(billing_usage_log_id, vendor_account_id,
                                                   balance_before, balance_after, deducted_amount,
                                                   balance_currency)
            VALUES(#{billingUsageLogId}, #{vendorAccountId}, #{balanceBefore}, #{balanceAfter},
                   #{deductedAmount}, #{balanceCurrency})
            """)
    void insertAdjustment(@Param("billingUsageLogId") Long billingUsageLogId,
                          @Param("vendorAccountId") Long vendorAccountId,
                          @Param("balanceBefore") BigDecimal balanceBefore,
                          @Param("balanceAfter") BigDecimal balanceAfter,
                          @Param("deductedAmount") BigDecimal deductedAmount,
                          @Param("balanceCurrency") String balanceCurrency);
}
