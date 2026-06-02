package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;

public interface VendorBalanceAdapter {

    boolean supports(String vendorCode);

    BalanceQueryResult query(ModelVendorAccount account);
}
