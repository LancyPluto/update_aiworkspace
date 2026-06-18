package com.aiminilab.aitoolmarket.agent.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekBalanceAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseBody_readsTotalBalance() throws Exception {
        String json = """
                {
                  "is_available": true,
                  "balance_infos": [
                    {
                      "currency": "CNY",
                      "total_balance": "128.50",
                      "granted_balance": "10.00",
                      "topped_up_balance": "118.50"
                    }
                  ]
                }
                """;
        BalanceQueryResult result = DeepSeekBalanceAdapter.parseBody(objectMapper, json);
        assertTrue(result.success());
        assertEquals(new BigDecimal("128.50"), result.balanceAmount());
        assertEquals("CNY", result.balanceCurrency());
    }

    @Test
    void parseBody_doesNotMarkPositiveBalanceAsInsufficientWhenNotAvailable() throws Exception {
        String json = """
                {
                  "is_available": false,
                  "balance_infos": [
                    {"currency": "CNY", "total_balance": "0.01"}
                  ]
                }
                """;
        BalanceQueryResult result = DeepSeekBalanceAdapter.parseBody(objectMapper, json);
        assertTrue(result.success());
        assertEquals(null, result.balanceStatus());
    }

    @Test
    void parseBody_marksNegativeBalanceAsInsufficient() throws Exception {
        String json = """
                {
                  "is_available": false,
                  "balance_infos": [
                    {"currency": "CNY", "total_balance": "-0.01"}
                  ]
                }
                """;
        BalanceQueryResult result = DeepSeekBalanceAdapter.parseBody(objectMapper, json);
        assertTrue(result.success());
        assertEquals("SUSPECTED_INSUFFICIENT", result.balanceStatus());
    }
}
