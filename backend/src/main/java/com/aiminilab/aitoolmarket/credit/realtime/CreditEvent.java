package com.aiminilab.aitoolmarket.credit.realtime;

import java.time.OffsetDateTime;

public record CreditEvent(String type, OffsetDateTime occurredAt) {

    public static CreditEvent accountChanged() {
        return new CreditEvent("CREDIT_ACCOUNT_CHANGED", OffsetDateTime.now());
    }
}
