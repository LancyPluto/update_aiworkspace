package com.aiminilab.aitoolmarket.common.enums;

import java.util.Set;

public enum RechargeOrderStatus {
    WAITING_PAYMENT,
    PAID,
    CREDITED,
    CLOSED,
    FAILED;

    private static final Set<RechargeOrderStatus> TERMINAL = Set.of(CREDITED, CLOSED, FAILED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitTo(RechargeOrderStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case WAITING_PAYMENT -> next == PAID || next == CLOSED || next == FAILED;
            case PAID -> next == CREDITED || next == FAILED;
            case CLOSED, FAILED -> next == PAID;
            case CREDITED -> false;
        };
    }
}
