package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class VolcengineBalanceAdapter implements VendorBalanceAdapter {

    private static final String DEFAULT_ORIGIN = "https://ark.cn-beijing.volces.com";

    private final OpenAiCompatibleBalanceProbe probe;

    public VolcengineBalanceAdapter(ObjectMapper objectMapper) {
        this.probe = new OpenAiCompatibleBalanceProbe(objectMapper);
    }

    @Override
    public boolean supports(String vendorCode) {
        return "volcengine".equalsIgnoreCase(vendorCode);
    }

    @Override
    public BalanceQueryResult query(ModelVendorAccount account) {
        BalanceQueryResult result = probe.probe(account, DEFAULT_ORIGIN);
        if (result.success()) {
            return result;
        }
        return BalanceQueryResult.unsupported(
                "火山方舟暂无公开余额 API，请在控制台费用中心查看，或在账户设置中手填余额");
    }
}
