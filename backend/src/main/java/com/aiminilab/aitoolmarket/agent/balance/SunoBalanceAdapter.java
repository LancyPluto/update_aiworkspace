package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import org.springframework.stereotype.Component;

@Component
public class SunoBalanceAdapter implements VendorBalanceAdapter {

    @Override
    public boolean supports(String vendorCode) {
        return "suno".equalsIgnoreCase(vendorCode) || "suno_music".equalsIgnoreCase(vendorCode);
    }

    @Override
    public BalanceQueryResult query(ModelVendorAccount account) {
        return BalanceQueryResult.unsupported(
                "Suno 暂未提供可用于该后台自动刷新的余额 API，请打开 Suno billing 页面查看，或在账户设置中手填余额。"
        );
    }
}
