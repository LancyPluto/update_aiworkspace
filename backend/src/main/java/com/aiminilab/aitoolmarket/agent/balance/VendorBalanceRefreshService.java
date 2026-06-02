package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class VendorBalanceRefreshService {

    private final ModelVendorAccountMapper vendorAccountMapper;
    private final VendorBalanceAdapterRegistry adapterRegistry;

    public VendorBalanceRefreshService(ModelVendorAccountMapper vendorAccountMapper,
                                       VendorBalanceAdapterRegistry adapterRegistry) {
        this.vendorAccountMapper = vendorAccountMapper;
        this.adapterRegistry = adapterRegistry;
    }

    public void refresh(ModelVendorAccount account) {
        String mode = normalizeMode(account.getBalanceQueryMode());
        LocalDateTime now = LocalDateTime.now();
        account.setBalanceUpdatedAt(now);

        if ("NONE".equals(mode)) {
            account.setBalanceStatus("UNKNOWN");
            account.setBalanceErrorMessage("该厂商不支持自动余额查询，请打开余额页或在账户设置中手填");
            return;
        }

        if ("MANUAL".equals(mode)) {
            recalculateFromManualAmount(account);
            account.setBalanceErrorMessage(null);
            return;
        }

        if ("REST_API".equals(mode) || "INFERRED".equals(mode)) {
            if (!adapterRegistry.hasRestAdapter(account.getVendorCode())) {
                account.setBalanceStatus("UNKNOWN");
                account.setBalanceErrorMessage("该厂商暂未接入 REST 余额查询，请改用手填或外链");
                return;
            }
            BalanceQueryResult result = adapterRegistry.query(account);
            applyQueryResult(account, result);
            return;
        }

        recalculateFromManualAmount(account);
    }

    public int refreshAllEnabled() {
        int count = 0;
        for (ModelVendorAccount account : vendorAccountMapper.findAllActive()) {
            if (!Boolean.TRUE.equals(account.getEnabled())) {
                continue;
            }
            String mode = normalizeMode(account.getBalanceQueryMode());
            if (!"REST_API".equals(mode)) {
                continue;
            }
            refresh(account);
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
            count++;
        }
        return count;
    }

    private void applyQueryResult(ModelVendorAccount account, BalanceQueryResult result) {
        if (!result.success()) {
            account.setBalanceErrorMessage(result.errorMessage());
            String status = result.balanceStatus() != null ? result.balanceStatus() : "ERROR";
            account.setBalanceStatus(status);
            if ("UNKNOWN".equals(status)) {
                account.setBalanceAmount(null);
            }
            return;
        }
        account.setBalanceAmount(result.balanceAmount());
        if (result.balanceCurrency() != null) {
            account.setBalanceCurrency(result.balanceCurrency());
        }
        account.setBalanceErrorMessage(null);
        recalculateFromManualAmount(account);
        if ("SUSPECTED_INSUFFICIENT".equals(result.balanceStatus())) {
            account.setBalanceStatus("SUSPECTED_INSUFFICIENT");
            account.setBalanceErrorMessage(result.errorMessage());
        }
    }

    private void recalculateFromManualAmount(ModelVendorAccount account) {
        if ("SUSPECTED_INSUFFICIENT".equals(account.getBalanceStatus())) {
            return;
        }
        var amount = account.getBalanceAmount();
        var threshold = account.getBalanceLowThreshold();
        if (amount == null) {
            account.setBalanceStatus("UNKNOWN");
            return;
        }
        if (threshold != null && amount.compareTo(threshold) < 0) {
            account.setBalanceStatus("LOW");
        } else {
            account.setBalanceStatus("OK");
        }
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "MANUAL";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }
}
