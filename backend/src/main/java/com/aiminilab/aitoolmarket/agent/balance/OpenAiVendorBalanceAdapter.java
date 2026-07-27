package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OpenAiVendorBalanceAdapter implements VendorBalanceAdapter {

    private static final String DEFAULT_ORIGIN = "https://api.openai.com";

    private final OpenAiCompatibleBalanceProbe probe;

    public OpenAiVendorBalanceAdapter(ObjectMapper objectMapper) {
        this.probe = new OpenAiCompatibleBalanceProbe(objectMapper);
    }

    @Override
    public boolean supports(String vendorCode) {
        return vendorCode != null && "openai".equalsIgnoreCase(vendorCode.trim());
    }

    @Override
    public BalanceQueryResult query(ModelVendorAccount account) {
        return probe.probe(account, DEFAULT_ORIGIN);
    }
}
