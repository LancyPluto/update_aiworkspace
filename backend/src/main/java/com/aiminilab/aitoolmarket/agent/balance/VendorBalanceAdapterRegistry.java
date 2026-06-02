package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class VendorBalanceAdapterRegistry {

    private final List<VendorBalanceAdapter> adapters;

    public VendorBalanceAdapterRegistry(List<VendorBalanceAdapter> adapters) {
        this.adapters = adapters;
    }

    public Optional<VendorBalanceAdapter> findAdapter(String vendorCode) {
        if (vendorCode == null || vendorCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = vendorCode.trim().toLowerCase(Locale.ROOT);
        return adapters.stream().filter(adapter -> adapter.supports(normalized)).findFirst();
    }

    public BalanceQueryResult query(ModelVendorAccount account) {
        return findAdapter(account.getVendorCode())
                .map(adapter -> adapter.query(account))
                .orElse(BalanceQueryResult.unsupported("该厂商暂未接入自动余额查询"));
    }

    public boolean hasRestAdapter(String vendorCode) {
        return findAdapter(vendorCode).isPresent();
    }
}
