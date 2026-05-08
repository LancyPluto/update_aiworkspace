package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;

public interface CreditService {
    CreditAccountResponse account(Long userId);

    void deductForTask(Long userId, Long taskId, int amount);

    ManualAddCreditsResponse manualAdd(Long userId, int amount, String reason, Long operatorId);

    Integer balance(Long userId);
}
