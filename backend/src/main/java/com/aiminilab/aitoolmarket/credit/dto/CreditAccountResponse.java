package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;

public record CreditAccountResponse(
        Long accountId,
        Long userId,
        Integer balance,
        Integer frozen,
        Integer available,
        Integer totalGranted,
        Integer totalConsumed,
        String status
) {
    public static CreditAccountResponse from(CreditAccount account) {
        return new CreditAccountResponse(
                account.getId(),
                account.getUserId(),
                account.getBalance(),
                account.getFrozen(),
                account.getBalance() - account.getFrozen(),
                account.getTotalGranted(),
                account.getTotalConsumed(),
                account.getStatus()
        );
    }
}
