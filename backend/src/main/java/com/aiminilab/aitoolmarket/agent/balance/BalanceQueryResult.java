package com.aiminilab.aitoolmarket.agent.balance;

import java.math.BigDecimal;

public record BalanceQueryResult(
        boolean success,
        BigDecimal balanceAmount,
        String balanceCurrency,
        String balanceStatus,
        String errorMessage
) {
    public static BalanceQueryResult ok(BigDecimal amount, String currency) {
        return new BalanceQueryResult(true, amount, currency == null ? "CNY" : currency, null, null);
    }

    public static BalanceQueryResult failed(String message) {
        return new BalanceQueryResult(false, null, null, "ERROR", message);
    }

    public static BalanceQueryResult unsupported(String message) {
        return new BalanceQueryResult(false, null, null, "UNKNOWN", message);
    }
}
