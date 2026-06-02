package com.aiminilab.aitoolmarket.agent.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimaxBalanceAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseSiliconFlowStyleUserInfo() throws Exception {
        String body = """
                {
                  "code": 20000,
                  "data": {
                    "totalBalance": "12.34",
                    "currency": "CNY"
                  }
                }
                """;
        OpenAiCompatibleBalanceProbe probe = new OpenAiCompatibleBalanceProbe(objectMapper);
        BalanceQueryResult result = probe.parseBody(body);
        assertTrue(result.success());
        assertEquals(new BigDecimal("12.34"), result.balanceAmount());
        assertEquals("CNY", result.balanceCurrency());
    }
}
