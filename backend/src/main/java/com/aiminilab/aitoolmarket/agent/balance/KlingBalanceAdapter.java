package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KlingBalanceAdapter implements VendorBalanceAdapter {

    private static final String DEFAULT_ORIGIN = "https://api-beijing.klingai.com";

    private final OpenAiCompatibleBalanceProbe probe;

    public KlingBalanceAdapter(ObjectMapper objectMapper) {
        this.probe = new OpenAiCompatibleBalanceProbe(objectMapper);
    }

    @Override
    public boolean supports(String vendorCode) {
        return "kling".equalsIgnoreCase(vendorCode);
    }

    @Override
    public BalanceQueryResult query(ModelVendorAccount account) {
        BalanceQueryResult result = probe.probe(account, DEFAULT_ORIGIN);
        if (result.success()) {
            return result;
        }
        return BalanceQueryResult.unsupported(
                "可灵暂未提供余额查询 API，请在控制台查看资源包，或在账户设置中手填余额");
    }
}
