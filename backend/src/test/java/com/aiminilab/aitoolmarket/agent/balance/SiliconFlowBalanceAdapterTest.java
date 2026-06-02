package com.aiminilab.aitoolmarket.agent.balance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiliconFlowBalanceAdapterTest {

    @Test
    void parseBody_readsTotalBalanceFromData() throws Exception {
        String json = """
                {
                  "code": 20000,
                  "message": "OK",
                  "status": true,
                  "data": {
                    "balance": "0.88",
                    "chargeBalance": "88.00",
                    "totalBalance": "88.88"
                  }
                }
                """;
        BalanceQueryResult result = SiliconFlowBalanceAdapter.parseBody(json);
        assertTrue(result.success());
        assertEquals(new BigDecimal("88.88"), result.balanceAmount());
        assertEquals("CNY", result.balanceCurrency());
    }
}
